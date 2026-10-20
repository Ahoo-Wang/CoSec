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
package me.ahoo.cosec.authorization

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.policy.action.clearPathVariables
import me.ahoo.cosec.policy.requiresBlocking
import me.ahoo.cosec.policy.verifyScoped
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

/**
 * Simple Authorization implementation.
 *
 * This class provides the core authorization logic by evaluating:
 * 1. Blacklist checks (apply to root as well)
 * 2. Root user bypass, only if enabled through [rootPrincipalMatcher] (root users get ALLOW unless blacklisted)
 * 3. Global policies
 * 4. Principal-specific policies
 * 5. Role-based permissions: the principal's roles scoped to the request's space ([RoleScope]), granted in the
 *    request's app
 *
 * Layers 3-5 are combined deny-first: an EXPLICIT_DENY matched in any layer wins over
 * an ALLOW matched in any other layer. A principal policy the repository does not have fails the
 * authorization with [MissingPolicyException] instead of being skipped.
 *
 * Matchers are evaluated synchronously on the subscribing thread. A layer whose policies or app permission
 * contain a blocking matcher (see [me.ahoo.cosec.policy.BlockingAware], e.g. a custom matcher that calls a remote service) is evaluated
 * on [Schedulers.boundedElastic] instead, so it never blocks an event loop even when the repositories answer
 * from the local cache. Layers without one are evaluated in place.
 *
 * @param policyRepository Repository for accessing policies
 * @param appRolePermissionRepository Repository for accessing role permissions
 * @param blacklistChecker Optional blacklist checker for blocking requests
 * @param rootPrincipalMatcher Decides which principals are root (bypass every check except the blacklist). Defaults
 *        to [RootPrincipalMatcher.NONE], like the starter's `cosec.authorization.root-enabled=false`.
 */
class SimpleAuthorization(
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
    private val blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
    private val rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
) : DecisionAuthorization {
    companion object {
        private val log = KotlinLogging.logger {}
        private val ROOT_DECISION = AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext)
        private val BLACKLIST_DECISION = AuthorizationDecision(AuthorizeResult.EXPLICIT_DENY, BlacklistVerifyContext)
        private val IMPLICIT_DENY_DECISION = AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY, NoMatchVerifyContext)
        private val DENY_FIRST = arrayOf(Effect.DENY, Effect.ALLOW)

        private fun Effect.decisiveResult(): VerifyResult = when (this) {
            Effect.DENY -> VerifyResult.EXPLICIT_DENY
            Effect.ALLOW -> VerifyResult.ALLOW
        }
    }

    /**
     * Deny-first evaluation of policy statements: every DENY statement of every matched policy is verified
     * before any ALLOW statement; first match wins.
     *
     * Policy conditions are evaluated exactly once, up front, for every policy, regardless of which statement
     * decides. Built-in conditions are pure: rate limiting is not a condition but
     * [me.ahoo.cosec.limiter.RateLimitingAuthorization], which spends permits only for allowed requests.
     *
     * Path variables are cleared before the policy conditions and before every statement (see [verifyScoped]), so a
     * condition only sees the variables its own statement's action captured, never those another statement, policy
     * or layer left.
     *
     * Plain loops over the source lists keep this hot path allocation-free apart from the matched-policy list.
     */
    private fun verifyPolicies(
        source: PolicyVerifySource,
        policies: List<Policy>,
        request: Request,
        securityContext: SecurityContext
    ): VerifyContext? {
        securityContext.clearPathVariables()
        val matchedPolicies = policies.filter { policy ->
            policy.condition.match(request = request, securityContext = securityContext)
        }
        for (effect in DENY_FIRST) {
            val decisive = effect.decisiveResult()
            for (policy in matchedPolicies) {
                policy.statements.forEachIndexed { index, statement ->
                    if (statement.effect == effect && statement.verifyScoped(request, securityContext) == decisive) {
                        log.debug {
                            "Verify [$request] [$securityContext] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [$decisive]."
                        }
                        return PolicyVerifyContext(
                            source = source,
                            policy = policy,
                            statementIndex = index,
                            statement = statement,
                            result = decisive,
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Deny-first evaluation of the principal's role permissions, iterating the role → permission-id
     * assignments directly instead of materializing [AppRolePermission.rolePermissionIndexer] per request.
     */
    private fun verifyAppRolePermission(
        appRolePermission: AppRolePermission,
        request: Request,
        context: SecurityContext
    ): VerifyContext? {
        val appPermission = appRolePermission.appPermission
        context.clearPathVariables()
        if (!appPermission.condition.match(request, context)) {
            return null
        }
        val permissionIndexer = appPermission.permissionIndexer
        for (effect in DENY_FIRST) {
            val decisive = effect.decisiveResult()
            for (rolePermission in appRolePermission.rolePermissions) {
                rolePermission.forEachPermission(permissionIndexer) { permission ->
                    if (permission.effect == effect && permission.verifyScoped(request, context) == decisive) {
                        log.debug {
                            "Verify [$request] [$context] matched Role[${rolePermission.id}] Permission[${permission.id}][${permission.name}] - [$decisive]."
                        }
                        return RoleVerifyContext(
                            roleId = rolePermission.id,
                            permission = permission,
                            result = decisive,
                        )
                    }
                }
            }
        }
        return null
    }

    private inline fun RolePermission.forEachPermission(
        permissionIndexer: Map<PermissionId, Permission>,
        action: (Permission) -> Unit
    ) {
        if (permissions.contains(AppRolePermission.ALL_PERMISSION_ID)) {
            permissionIndexer.values.forEach(action)
            return
        }
        permissions.forEach { permissionId ->
            permissionIndexer[permissionId]?.let(action)
        }
    }

    /**
     * Runs [verifier] in place, or on [Schedulers.boundedElastic] when it may block.
     * Exceptions thrown by [verifier] (e.g. `RegexTimeoutException`) become error signals either way.
     */
    private inline fun evaluate(
        requiresBlocking: Boolean,
        crossinline verifier: () -> VerifyContext?
    ): Mono<VerifyContext> {
        if (!requiresBlocking) {
            return Mono.justOrEmpty(verifier())
        }
        return Mono.fromCallable { verifier() }.subscribeOn(Schedulers.boundedElastic())
    }

    private fun List<Policy>.anyRequiresBlocking(): Boolean {
        for (policy in this) {
            if (policy.requiresBlocking) {
                return true
            }
        }
        return false
    }

    /**
     * [PolicyRepository.getPolicies] returns only the policies it found. A missing one may be the deny that keeps
     * the request out, so evaluating the rest could widen access. A nested scan: principals list few policies.
     */
    private fun List<Policy>.containsAll(policyIds: Set<String>): Boolean {
        for (policyId in policyIds) {
            if (none { it.id == policyId }) {
                return false
            }
        }
        return true
    }

    private fun verifyRoot(context: SecurityContext): VerifyResult =
        if (rootPrincipalMatcher.isRoot(context.principal)) {
            log.debug {
                "Verify [$context] matched Root - [Allow]."
            }
            VerifyResult.ALLOW
        } else {
            VerifyResult.IMPLICIT_DENY
        }

    private fun verifyGlobalPolicies(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> =
        policyRepository
            .getGlobalPolicy()
            .flatMap { policies: List<Policy> ->
                evaluate(policies.anyRequiresBlocking()) {
                    verifyPolicies(PolicyVerifySource.GLOBAL, policies, request, context)
                }
            }

    private fun verifyPrincipalPolicies(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> {
        if (context.principal.policies.isEmpty()) {
            return Mono.empty()
        }
        val policyIds = context.principal.policies
        return policyRepository
            .getPolicies(policyIds)
            .defaultIfEmpty(emptyList())
            .flatMap { policies: List<Policy> ->
                if (!policies.containsAll(policyIds)) {
                    return@flatMap Mono.error(MissingPolicyException(policyIds - policies.map { it.id }.toSet()))
                }
                evaluate(policies.anyRequiresBlocking()) {
                    verifyPolicies(PolicyVerifySource.PRINCIPAL, policies, request, context)
                }
            }
    }

    /**
     * Only the principal's roles scoped to the request's space count (see [RoleScope]); the repository keys their
     * grants by the request's app, so a grant never applies in another app or space.
     */
    private fun verifyAppRolePermission(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> {
        val principalRoles = context.principal.roles
        if (principalRoles.isEmpty()) {
            return Mono.empty()
        }
        val spaceId = request.spaceId
        val roles = RoleScope.rolesIn(spaceId, principalRoles)
        if (roles.isEmpty()) {
            return Mono.empty()
        }
        return appRolePermissionRepository
            .getAppRolePermission(request.appId, spaceId, roles)
            .flatMap { appRolePermission ->
                evaluate(appRolePermission.appPermission.requiresBlocking) {
                    verifyAppRolePermission(appRolePermission, request, context)
                }
            }
    }

    /**
     * Chains the next verification layer so that an explicit deny in any layer wins:
     * a matched EXPLICIT_DENY short-circuits, while a matched ALLOW is only granted
     * once the remaining layers have produced no EXPLICIT_DENY.
     */
    private fun Mono<VerifyContext>.thenVerify(nextLayer: () -> Mono<VerifyContext>): Mono<VerifyContext> =
        flatMap { matched ->
            if (matched.result == VerifyResult.EXPLICIT_DENY) {
                return@flatMap matched.toMono()
            }
            nextLayer()
                .filter { it.result == VerifyResult.EXPLICIT_DENY }
                .defaultIfEmpty(matched)
        }.switchIfEmpty {
            nextLayer()
        }

    private fun verify(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizationDecision> =
        verifyGlobalPolicies(request, context)
            .thenVerify {
                verifyPrincipalPolicies(request, context)
            }.thenVerify {
                verifyAppRolePermission(request, context)
            }.map {
                context.setVerifyContext(it)
                AuthorizationDecision(it.result.toAuthorizeResult(), it)
            }.switchIfEmpty {
                log.debug {
                    "Verify [$request] [$context] No policies matched - [Implicit Deny]."
                }
                IMPLICIT_DENY_DECISION.toMono()
            }

    override fun decide(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizationDecision> = Mono.defer {
        blacklistChecker
            .check(request, context)
            .flatMap { allowed ->
                if (!allowed) {
                    log.debug {
                        "Request [$request] is blocked by the blacklist."
                    }
                    return@flatMap BLACKLIST_DECISION.toMono()
                }
                if (verifyRoot(context) == VerifyResult.ALLOW) {
                    return@flatMap ROOT_DECISION.toMono()
                }
                verify(request, context)
            }
    }
}
