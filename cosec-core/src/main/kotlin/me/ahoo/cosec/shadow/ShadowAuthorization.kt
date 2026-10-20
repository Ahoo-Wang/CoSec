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

package me.ahoo.cosec.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.authorization.decide
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.toDecisionRef
import me.ahoo.cosec.policy.markPolicyEvaluation
import reactor.core.publisher.Mono
import kotlin.random.Random

/**
 * Evaluates candidate policies next to the enforced ones and reports where they decide differently, without ever
 * changing a response: the [delegate]'s decision is always the one returned.
 *
 * The candidates come from [policyProvider], policies laid over the enforced ones by id and app permissions by app id, and are evaluated by a second
 * [SimpleAuthorization] on a fresh security context marked as a policy evaluation, so custom matchers can skip
 * side effects. The candidate run happens inline, while the request is still alive, because transport requests
 * (servlet requests in particular) must not be read after they complete. It costs a second evaluation on every
 * sampled request, so bound it with [sampleRate]. Its failures and the [sink]'s are logged and dropped.
 *
 * @param sampleRate the share of allowed and denied requests to shadow, from 0 to 1
 * @param reportRuleChanges also report [ShadowDiffKind.RULE_CHANGED] when the result is the same
 */
@Suppress("LongParameterList")
class ShadowAuthorization(
    override val delegate: Authorization,
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
    private val policyProvider: ShadowPolicyProvider,
    private val sink: ShadowEventSink = LoggingShadowEventSink(),
    private val blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
    private val rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
    private val sampleRate: Double = 1.0,
    private val reportRuleChanges: Boolean = false,
    private val random: Random = Random.Default
) : DecisionAuthorization,
    Delegated<Authorization> {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val ERROR_REASON = "Error"
    }

    init {
        require(sampleRate in 0.0..1.0) { "sampleRate must be between 0 and 1, but was [$sampleRate]." }
    }

    private class Candidate(
        val policies: List<Policy>,
        val appPermissions: List<AppPermission>,
        val authorization: Authorization
    )

    @Volatile
    private var candidate: Candidate? = null

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
        Mono.defer { delegate.decide(request, context) }
            .flatMap { enforced ->
                shadow(request, context, enforced).thenReturn(enforced)
            }

    private fun shadow(request: Request, context: SecurityContext, enforced: AuthorizationDecision): Mono<Void> {
        if (sampleRate < 1.0 && random.nextDouble() >= sampleRate) {
            return Mono.empty()
        }
        return Mono.defer {
            val authorization = candidateAuthorization() ?: return@defer Mono.empty()
            val shadowContext = SimpleSecurityContext(context.principal, context.tenant).markPolicyEvaluation()
            authorization.decide(request, shadowContext)
                .flatMap<ShadowDiff> {
                    Mono.justOrEmpty(
                        compare(request, context, enforced, outcomeOf(it), errorKind = null)
                    )
                }
                .onErrorResume { cause ->
                    val failed = ShadowOutcome(
                        authorized = false,
                        reason = ERROR_REASON,
                        decidedBy = null,
                        error = cause.toString(),
                    )
                    Mono.justOrEmpty(compare(request, context, enforced, failed, ShadowDiffKind.CANDIDATE_ERROR))
                }
        }.doOnNext(::publish)
            .onErrorResume { cause ->
                log.warn(cause) { "Shadow evaluation of [${request.path}] failed." }
                Mono.empty()
            }.then()
    }

    /** Rebuilt only when the provider returns different list instances. Null when there are no candidates. */
    private fun candidateAuthorization(): Authorization? {
        val policies = policyProvider.getPolicies()
        val appPermissions = policyProvider.getAppPermissions()
        if (policies.isEmpty() && appPermissions.isEmpty()) {
            return null
        }
        candidate?.takeIf { it.policies === policies && it.appPermissions === appPermissions }
            ?.let { return it.authorization }
        val authorization = SimpleAuthorization(
            OverlayPolicyRepository(policyRepository, policies),
            OverlayAppRolePermissionRepository(appRolePermissionRepository, appPermissions),
            blacklistChecker,
            rootPrincipalMatcher,
        )
        candidate = Candidate(policies, appPermissions, authorization)
        return authorization
    }

    private fun outcomeOf(decision: AuthorizationDecision): ShadowOutcome =
        ShadowOutcome(decision.result.authorized, decision.result.reason, decision.verifyContext?.toDecisionRef())

    private fun compare(
        request: Request,
        context: SecurityContext,
        enforcedDecision: AuthorizationDecision,
        candidate: ShadowOutcome,
        errorKind: ShadowDiffKind?
    ): ShadowDiff? {
        val enforced = outcomeOf(enforcedDecision)
        val kind = errorKind ?: when {
            !enforced.authorized && candidate.authorized -> ShadowDiffKind.WIDENED
            enforced.authorized && !candidate.authorized -> ShadowDiffKind.NARROWED
            reportRuleChanges && enforced.decidedBy != candidate.decidedBy -> ShadowDiffKind.RULE_CHANGED
            else -> return null
        }
        return ShadowDiff(
            kind = kind,
            requestId = request.requestId,
            tenantId = context.tenant.tenantId,
            principalId = context.principal.id,
            appId = request.appId,
            method = request.method,
            path = request.path,
            enforced = enforced,
            candidate = candidate,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun publish(diff: ShadowDiff) {
        try {
            sink.publish(diff)
        } catch (cause: Exception) {
            log.warn(cause) { "Failed to publish shadow diff." }
        }
    }
}
