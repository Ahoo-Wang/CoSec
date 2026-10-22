/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.explain

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RequestMatcher
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.pipeline.SecurityDecision
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.policy.action.clearPathVariables
import me.ahoo.cosec.policy.markPolicyEvaluation
import me.ahoo.cosec.policy.verifyScoped
import me.ahoo.cosec.token.TokenVerificationContexts.setTokenVerificationException
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.token.toAuthorizeResult
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * Explains authorization decisions for a principal and a request, without side effects.
 *
 * The decision comes from running [SimpleAuthorization] through [SecurityPipeline], the same code that decides real
 * requests, so it is authoritative. The trace is a separate walk over the same repository snapshot that classifies
 * every policy statement and role permission; it is checked against the decision, and a disagreement is reported
 * as [ExplainWarningCode.TRACE_INCONSISTENT] instead of being hidden.
 *
 * Both run with a security context marked as a policy evaluation (see `me.ahoo.cosec.policy.isPolicyEvaluation`),
 * so custom matchers can skip side effects. Rate limits are listed, never spent.
 *
 * An explanation reveals policy structure: expose it only to administrators.
 */
class AuthorizationExplainer internal constructor(
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
    private val blacklistChecker: BlacklistChecker,
    private val rootPrincipalMatcher: RootPrincipalMatcher,
    private val rateLimitRuleProvider: RateLimitRuleProvider?,
    private val securityContextParser: SecurityContextParser?,
    /**
     * Explain from the trace of the one evaluation that decides ([EngineAuthorization] at [TraceLevel.FULL] or
     * [TraceLevel.EXHAUSTIVE], RFC 0002 §5.2) instead of narrating. Only tests set it until the engine replaces the
     * legacy implementation: an explanation must explain the implementation that enforces.
     */
    private val fromTrace: Boolean
) {
    constructor(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
        rateLimitRuleProvider: RateLimitRuleProvider? = null,
        securityContextParser: SecurityContextParser? = null
    ) : this(
        policyRepository,
        appRolePermissionRepository,
        blacklistChecker,
        rootPrincipalMatcher,
        rateLimitRuleProvider,
        securityContextParser,
        fromTrace = false,
    )

    /**
     * Explains the decision for a described [principal]. Runs on [Schedulers.boundedElastic]: an explanation reads
     * repositories and may run blocking custom matchers.
     */
    fun explain(
        request: Request,
        principal: CoSecPrincipal,
        options: ExplainOptions = ExplainOptions()
    ): Mono<Explanation> = explain(request, options) { SimpleSecurityContext(principal) }

    /**
     * Explains the decision for whoever [request] authenticates as (its `Authorization` token or API key), parsed by
     * [securityContextParser] exactly as a real request would be, including revocation checks. A rejected credential
     * is explained as the anonymous request it becomes, with a [ExplainWarningCode.TOKEN_INVALID] warning.
     */
    fun explain(request: Request, options: ExplainOptions = ExplainOptions()): Mono<Explanation> {
        val parser = requireNotNull(securityContextParser) { "Explaining a token needs a SecurityContextParser." }
        return explain(request, options) { parser.parse(request) }
    }

    private fun explain(
        request: Request,
        options: ExplainOptions,
        contextOf: () -> SecurityContext
    ): Mono<Explanation> = Mono.defer {
        if (fromTrace) {
            return@defer explainFromTrace(request, options, contextOf)
        }
        val policies = SnapshotPolicyRepository(policyRepository)
        val permissions = SnapshotAppRolePermissionRepository(appRolePermissionRepository)
        decide(request, contextOf, policies, permissions).flatMap { decision ->
            val warnings = mutableListOf<ExplainWarning>()
            val narrator = Narrator(request, dryRunContext(contextOf, warnings), options, warnings)
            narrator.narrate(policies, permissions).map { trace -> explanation(decision, trace, narrator) }
        }
    }.subscribeOn(Schedulers.boundedElastic())

    /**
     * Decides once, with an [EngineAuthorization] that traces, through [SecurityPipeline], and explains that
     * evaluation: the status comes from the pipeline and everything else from the trace ([TraceExplanations]).
     */
    private fun explainFromTrace(
        request: Request,
        options: ExplainOptions,
        contextOf: () -> SecurityContext
    ): Mono<Explanation> {
        val warnings = mutableListOf<ExplainWarning>()
        val traced = AtomicReference<TracedEvaluation>()
        val authorization = EngineAuthorization(
            policyRepository,
            appRolePermissionRepository,
            blacklistChecker,
            rootPrincipalMatcher,
            if (options.exhaustive) TraceLevel.EXHAUSTIVE else TraceLevel.FULL,
        ) { evaluation, policies -> traced.set(TracedEvaluation(evaluation, policies)) }
        return pipeline(contextOf, authorization, warnings).evaluate(request).map { decision ->
            val explanation = TraceExplanations.explain(decision, traced.get(), options.detail)
            val allWarnings = (warnings + explanation.warnings).toMutableList()
            val rateLimitRules = decision.securityContext
                ?.takeIf { decision.allowed }
                ?.let { matchingRateLimitRules(request, it, allWarnings) }
                .orEmpty()
            explanation.copy(rateLimitRules = rateLimitRules, warnings = allWarnings)
        }
    }

    /**
     * The context [contextOf] produces, marked as a policy evaluation. A rejected credential becomes an anonymous
     * context carrying the rejection, which is how [SecurityPipeline] maps it to `401` with the token's reason.
     */
    private fun dryRunContext(
        contextOf: () -> SecurityContext,
        warnings: MutableList<ExplainWarning>? = null
    ): SecurityContext =
        try {
            contextOf().markPolicyEvaluation()
        } catch (rejected: TokenVerificationException) {
            warnings?.add(
                ExplainWarning(
                    ExplainWarningCode.TOKEN_INVALID,
                    "The credential was rejected: ${rejected.toAuthorizeResult().reason}."
                ),
            )
            SimpleSecurityContext.anonymous().markPolicyEvaluation().setTokenVerificationException(rejected)
        }

    private fun decide(
        request: Request,
        contextOf: () -> SecurityContext,
        policies: PolicyRepository,
        permissions: AppRolePermissionRepository
    ): Mono<SecurityDecision> {
        val authorization = SimpleAuthorization(policies, permissions, blacklistChecker, rootPrincipalMatcher)
        return pipeline(contextOf, authorization).evaluate(request)
    }

    private fun pipeline(
        contextOf: () -> SecurityContext,
        authorization: Authorization,
        warnings: MutableList<ExplainWarning>? = null
    ): SecurityPipeline<Request> {
        val requestParser = object : RequestParser<Request> {
            override fun parse(request: Request): Request = request
        }
        return SecurityPipeline(
            requestParser,
            SecurityContextParser { dryRunContext(contextOf, warnings) },
            authorization
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun matchingRateLimitRules(
        request: Request,
        context: SecurityContext,
        warnings: MutableList<ExplainWarning>
    ): List<String> {
        val rules = rateLimitRuleProvider?.getRules() ?: return emptyList()
        return rules.filter { rule ->
            try {
                rule.matches(request, context)
            } catch (cause: Exception) {
                warnings += ExplainWarning(
                    ExplainWarningCode.MATCHER_ERROR,
                    "Matching rate limit rule [${rule.id}] failed: $cause",
                )
                false
            }
        }.map { it.id }
    }

    private fun explanation(decision: SecurityDecision, trace: Trace, narrator: Narrator): Explanation {
        val warnings = trace.warnings.toMutableList()
        val decidedBy = decision.verifyContext?.toDecisionRef()
        // A matcher error, a missing principal policy or a missing granted permission aborts real authorization
        // (fail closed), which the trace does not model: it narrates the policies and permissions that were found.
        val aborted = warnings.any { it.code in ABORTING_WARNINGS }
        if (!aborted && decidedBy != trace.decidedBy) {
            warnings += ExplainWarning(
                ExplainWarningCode.TRACE_INCONSISTENT,
                "Authorization was decided by [$decidedBy], but the trace points at [${trace.decidedBy}].",
            )
        }
        val rateLimitRules = if (decision.allowed) narrator.matchingRateLimitRules(warnings) else emptyList()
        return Explanation(
            status = decision.status,
            authorized = decision.result.authorized,
            reason = decision.result.reason,
            decidedBy = decidedBy,
            layers = trace.layers,
            overriddenAllows = trace.overriddenAllows,
            rateLimitRules = rateLimitRules,
            warnings = warnings,
        )
    }

    private data class Trace(
        val layers: List<LayerTrace>,
        val decidedBy: DecisionRef?,
        val overriddenAllows: List<DecisionRef>,
        val warnings: List<ExplainWarning>
    )

    private data class Decisive(val result: VerifyResult, val ref: DecisionRef)

    /**
     * Walks the layers in the order and with the combination rules of [SimpleAuthorization].
     */
    private inner class Narrator(
        private val request: Request,
        private val context: SecurityContext,
        private val options: ExplainOptions,
        private val warnings: MutableList<ExplainWarning>
    ) {

        fun narrate(policies: PolicyRepository, permissions: AppRolePermissionRepository): Mono<Trace> {
            val principal = context.principal
            return blacklistChecker.check(request, context).flatMap { allowed ->
                if (!allowed) {
                    val blacklist = DecisionRef(ExplainLayer.BLACKLIST)
                    val blacklistLayer =
                        LayerTrace(ExplainLayer.BLACKLIST, LayerState.EVALUATED, VerifyResult.EXPLICIT_DENY, blacklist)
                    val layers = listOf(blacklistLayer, notEvaluated(ExplainLayer.ROOT)) +
                        POLICY_LAYERS.map { notEvaluated(it) }
                    return@flatMap Trace(layers, blacklist, emptyList(), warnings).toMono()
                }
                val blacklistLayer = LayerTrace(ExplainLayer.BLACKLIST, LayerState.EVALUATED, VerifyResult.ALLOW)
                if (rootPrincipalMatcher.isRoot(principal)) {
                    val root = DecisionRef(ExplainLayer.ROOT)
                    val rootLayer = LayerTrace(ExplainLayer.ROOT, LayerState.EVALUATED, VerifyResult.ALLOW, root)
                    val layers = listOf(blacklistLayer, rootLayer) + POLICY_LAYERS.map { notEvaluated(it) }
                    return@flatMap Trace(layers, root, emptyList(), warnings).toMono()
                }
                val rootLayer = LayerTrace(ExplainLayer.ROOT, LayerState.EVALUATED, VerifyResult.IMPLICIT_DENY)
                Mono.zip(
                    policies.getGlobalPolicy().defaultIfEmpty(emptyList()),
                    principalPolicies(policies),
                    appRolePermission(permissions),
                ).map { sources ->
                    narrateLayers(listOf(blacklistLayer, rootLayer), sources.t1, sources.t2, sources.t3.orElse(null))
                }
            }
        }

        private fun principalPolicies(policies: PolicyRepository): Mono<Optional<List<Policy>>> {
            val policyIds = context.principal.policies
            if (policyIds.isEmpty()) {
                return Mono.just(Optional.empty())
            }
            return policies.getPolicies(policyIds).defaultIfEmpty(emptyList()).map { found ->
                (policyIds - found.map { it.id }.toSet()).forEach { missing ->
                    warnings += ExplainWarning(
                        ExplainWarningCode.MISSING_POLICY,
                        "The principal lists policy [$missing], which the repository does not have; " +
                            "authorization fails closed.",
                    )
                }
                Optional.of(found)
            }
        }

        private fun appRolePermission(permissions: AppRolePermissionRepository): Mono<Optional<AppRolePermission>> {
            val principalRoles = context.principal.roles
            if (principalRoles.isEmpty()) {
                return Mono.just(Optional.empty())
            }
            val spaceId = request.spaceId
            val roles = RoleScope.rolesIn(spaceId, principalRoles)
            if (roles.isEmpty()) {
                warnings += ExplainWarning(
                    ExplainWarningCode.NO_ROLE_IN_SPACE,
                    "The principal has roles $principalRoles, but none applies in space [$spaceId]: " +
                        "a role applies in a space only when written role@space, and in the default space " +
                        "only without a suffix.",
                )
                return Mono.just(Optional.empty())
            }
            return permissions.getAppRolePermission(request.appId, spaceId, roles)
                .map { Optional.of(it) }
                .defaultIfEmpty(Optional.empty())
                .doOnNext {
                    if (it.isEmpty) {
                        warnings += ExplainWarning(
                            ExplainWarningCode.NO_APP_PERMISSION,
                            "The principal has roles $roles in space [$spaceId], but app [${request.appId}] " +
                                "has no app permission, so none of its role grants apply.",
                        )
                    }
                }
        }

        private fun narrateLayers(
            head: List<LayerTrace>,
            globalPolicies: List<Policy>,
            principalPolicies: Optional<List<Policy>>,
            appRolePermission: AppRolePermission?
        ): Trace {
            val layers = head.toMutableList()
            val overridden = mutableListOf<DecisionRef>()
            var decided: Decisive? = null
            val evaluators: List<Pair<ExplainLayer, (LayerState) -> LayerTrace>> = listOf(
                ExplainLayer.GLOBAL_POLICY to { state -> policyLayer(ExplainLayer.GLOBAL_POLICY, state, globalPolicies) },
                ExplainLayer.PRINCIPAL_POLICY to { state ->
                    principalPolicies.map { policyLayer(ExplainLayer.PRINCIPAL_POLICY, state, it) }
                        .orElseGet { LayerTrace(ExplainLayer.PRINCIPAL_POLICY, LayerState.EMPTY) }
                },
                ExplainLayer.ROLE_PERMISSION to { state ->
                    appRolePermission?.let { roleLayer(state, it) }
                        ?: LayerTrace(ExplainLayer.ROLE_PERMISSION, LayerState.EMPTY)
                },
            )
            for ((layer, evaluate) in evaluators) {
                val afterDeny = decided?.result == VerifyResult.EXPLICIT_DENY
                if (afterDeny && !options.exhaustive) {
                    layers += notEvaluated(layer)
                    continue
                }
                val trace = evaluate(if (afterDeny) LayerState.EVALUATED_AFTER_DECISION else LayerState.EVALUATED)
                layers += trace
                if (afterDeny || trace.matched == null) {
                    continue
                }
                val current = decided
                if (current == null) {
                    decided = Decisive(checkNotNull(trace.result), trace.matched)
                } else if (trace.result == VerifyResult.EXPLICIT_DENY) {
                    overridden += current.ref
                    decided = Decisive(VerifyResult.EXPLICIT_DENY, trace.matched)
                }
            }
            return Trace(layers, decided?.ref, overridden, warnings)
        }

        private fun policyLayer(layer: ExplainLayer, state: LayerState, policies: List<Policy>): LayerTrace {
            if (policies.isEmpty()) {
                return LayerTrace(layer, LayerState.EMPTY)
            }
            val traces = policies.map { policy ->
                val conditionMatched = match(policy.condition) { "policy [${policy.id}] condition" }
                PolicyTrace(
                    id = policy.id,
                    name = policy.name,
                    conditionMatched = conditionMatched,
                    condition = policy.condition.toRef(),
                    statements = policy.statements.mapIndexed { index, statement ->
                        statementTrace(index, null, statement, conditionMatched == true) {
                            "policy [${policy.id}] statement [$index]"
                        }
                    },
                )
            }
            val matched = firstDecisive(traces.filter { it.conditionMatched == true }) { policy ->
                policy.statements.map { statement ->
                    statement to DecisionRef(layer, policy.id, statement.index, statement.name)
                }
            }
            return LayerTrace(
                layer = layer,
                state = state,
                result = matched?.result ?: VerifyResult.IMPLICIT_DENY,
                matched = matched?.ref,
                policies = traces,
            )
        }

        private fun roleLayer(state: LayerState, appRolePermission: AppRolePermission): LayerTrace {
            val appPermission = appRolePermission.appPermission
            val conditionMatched = match(appPermission.condition) { "app [${appPermission.id}] condition" }
            val permissionIndexer = appPermission.permissionIndexer
            val roles = appRolePermission.rolePermissions.map { rolePermission ->
                val grantsAll = rolePermission.permissions.contains(AppRolePermission.ALL_PERMISSION_ID)
                val granted: List<Permission> = if (grantsAll) {
                    permissionIndexer.values.toList()
                } else {
                    rolePermission.permissions.mapNotNull { permissionIndexer[it] }
                }
                RoleTrace(
                    roleId = rolePermission.id,
                    permissions = granted.mapIndexed { index, permission ->
                        statementTrace(index, permission.id, permission, conditionMatched == true) {
                            "role [${rolePermission.id}] permission [${permission.id}]"
                        }
                    },
                )
            }
            if (state == LayerState.EVALUATED && conditionMatched == true) {
                warnMissingPermission(appRolePermission, roles)
            }
            val matched = if (conditionMatched == true) {
                firstDecisive(roles) { role ->
                    role.permissions.map { permission ->
                        permission to DecisionRef(
                            layer = ExplainLayer.ROLE_PERMISSION,
                            statementName = permission.name,
                            roleId = role.roleId,
                            permissionId = permission.id,
                        )
                    }
                }
            } else {
                null
            }
            return LayerTrace(
                layer = ExplainLayer.ROLE_PERMISSION,
                state = state,
                result = matched?.result ?: VerifyResult.IMPLICIT_DENY,
                matched = matched?.ref,
                conditionMatched = conditionMatched,
                roles = roles,
            )
        }

        /**
         * Real authorization looks the granted ids up in its deny pass, in grant order, and fails on the first one the
         * catalog does not define, unless a deny matched before it. [roles] narrate the defined ids in that order.
         */
        private fun warnMissingPermission(appRolePermission: AppRolePermission, roles: List<RoleTrace>) {
            val appPermission = appRolePermission.appPermission
            val permissionIndexer = appPermission.permissionIndexer
            appRolePermission.rolePermissions.zip(roles).forEach { (rolePermission, role) ->
                val traces = role.permissions.iterator()
                val grantsAll = rolePermission.permissions.contains(AppRolePermission.ALL_PERMISSION_ID)
                val permissionIds = if (grantsAll) permissionIndexer.keys else rolePermission.permissions
                for (permissionId in permissionIds) {
                    if (!permissionIndexer.containsKey(permissionId)) {
                        val missing = rolePermission.permissions.filterNot { permissionIndexer.containsKey(it) }
                        warnings += ExplainWarning(
                            ExplainWarningCode.MISSING_PERMISSION,
                            "Role [${rolePermission.id}] is granted permissions $missing, which app " +
                                "[${appPermission.id}] does not define; authorization fails closed.",
                        )
                        return
                    }
                    val trace = traces.next()
                    if (trace.effect == Effect.DENY && trace.outcome == StatementOutcome.MATCHED) {
                        return
                    }
                }
            }
        }

        /** Deny-first: the first matched deny in source order, otherwise the first matched allow. */
        private fun <T> firstDecisive(
            sources: List<T>,
            statementsOf: (T) -> List<Pair<StatementTrace, DecisionRef>>
        ): Decisive? {
            val candidates = sources.flatMap(statementsOf).filter { it.first.outcome == StatementOutcome.MATCHED }
            candidates.firstOrNull { it.first.effect == Effect.DENY }?.let {
                return Decisive(VerifyResult.EXPLICIT_DENY, it.second)
            }
            return candidates.firstOrNull()?.let { Decisive(VerifyResult.ALLOW, it.second) }
        }

        private fun statementTrace(
            index: Int,
            id: String?,
            statement: Statement,
            evaluated: Boolean,
            where: () -> String
        ): StatementTrace {
            val trace = StatementTrace(
                index = index,
                id = id,
                name = statement.name,
                effect = statement.effect,
                outcome = StatementOutcome.NOT_EVALUATED,
                action = statement.action.toRef(),
                condition = statement.condition.toRef(),
            )
            if (!evaluated) {
                return trace
            }
            @Suppress("TooGenericExceptionCaught")
            return try {
                val outcome = when {
                    statement.verifyScoped(request, context) != VerifyResult.IMPLICIT_DENY -> StatementOutcome.MATCHED
                    !statement.action.match(request, context) -> StatementOutcome.ACTION_MISS
                    else -> StatementOutcome.CONDITION_MISS
                }
                trace.copy(outcome = outcome)
            } catch (cause: Exception) {
                matcherError(where(), cause)
                trace.copy(outcome = StatementOutcome.ERROR, error = cause.toString())
            }
        }

        /** Matches a policy or app condition, which runs before any action and so sees no path variables. */
        @Suppress("TooGenericExceptionCaught")
        private fun match(condition: ConditionMatcher, where: () -> String): Boolean? =
            try {
                context.clearPathVariables()
                condition.match(request, context)
            } catch (cause: Exception) {
                matcherError(where(), cause)
                null
            }

        private fun matcherError(where: String, cause: Exception) {
            warnings += ExplainWarning(ExplainWarningCode.MATCHER_ERROR, "Matching $where failed: $cause")
        }

        private fun RequestMatcher.toRef(): MatcherRef =
            MatcherRef(type, configuration.takeIf { options.detail == ExplainDetail.FULL })

        fun matchingRateLimitRules(warnings: MutableList<ExplainWarning>): List<String> =
            matchingRateLimitRules(request, context, warnings)
    }

    private companion object {
        val ABORTING_WARNINGS = setOf(
            ExplainWarningCode.MATCHER_ERROR,
            ExplainWarningCode.MISSING_POLICY,
            ExplainWarningCode.MISSING_PERMISSION,
        )
        val POLICY_LAYERS = listOf(
            ExplainLayer.GLOBAL_POLICY,
            ExplainLayer.PRINCIPAL_POLICY,
            ExplainLayer.ROLE_PERMISSION
        )

        fun notEvaluated(layer: ExplainLayer) = LayerTrace(layer, LayerState.NOT_EVALUATED)

        fun <T : Any> T.toMono(): Mono<T> = Mono.just(this)
    }
}
