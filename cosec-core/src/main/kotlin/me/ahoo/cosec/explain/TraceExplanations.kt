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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.RequestMatcher
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.engine.AppPermissionGroup
import me.ahoo.cosec.authorization.engine.PolicyGroup
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.GroupTrace
import me.ahoo.cosec.engine.GuardOutcome
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.RuleOutcome
import me.ahoo.cosec.engine.RuleTrace
import me.ahoo.cosec.engine.Trace
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef
import me.ahoo.cosec.pipeline.SecurityDecision
import me.ahoo.cosec.engine.LayerState as EngineLayerState
import me.ahoo.cosec.engine.LayerTrace as EngineLayerTrace
import me.ahoo.cosec.model.Effect as EngineEffect

/** The one evaluation behind a decision, and the policy set it evaluated. */
internal class TracedEvaluation(val evaluation: Evaluation, val policies: PolicySet)

/**
 * An [Explanation] from the [Trace] of the evaluation that decided, and the [SecurityDecision] the pipeline made of it
 * (RFC 0002 §5.2). A pure mapping: it evaluates nothing, so the explanation cannot disagree with the decision.
 *
 * Every outcome, position and rule identity comes from the trace. The adapter's [PolicySet] only supplies what the
 * trace leaves out because the decision never needs it: policy names and matcher types and configurations, looked up
 * by the trace's refs. The pipeline's request and principal supply the details of the role layer's warnings.
 *
 * Unlike the narration, a layer the decision did not reach is [LayerState.NOT_EVALUATED] (or, exhaustively,
 * [LayerState.EVALUATED_AFTER_DECISION]) also when the decision is an error, and an indeterminate layer has no
 * result (RFC 0002 X6).
 */
internal object TraceExplanations {
    /**
     * [traced] is null when the pipeline decided without authorizing (a request it could not parse, or a failed
     * blacklist check): then no layer was evaluated. Rate limit rules are not part of the trace; the caller adds them.
     *
     * Throws the read error of a layer whose data was unavailable, as the narration fails when it reads that layer.
     */
    fun explain(decision: SecurityDecision, traced: TracedEvaluation?, detail: ExplainDetail): Explanation {
        val trace = traced?.evaluation?.trace
        val warnings = mutableListOf<ExplainWarning>()
        val layers = if (trace == null || traced == null) {
            ExplainLayer.entries.map { LayerTrace(it, LayerState.NOT_EVALUATED) }
        } else {
            Mapper(decision, traced.policies, detail, warnings).layers(trace)
        }
        val engineDecision = traced?.evaluation?.decision
        val overriddenAllows = if (trace != null && engineDecision != null) {
            overridden(engineDecision, trace)
        } else {
            emptyList()
        }
        return Explanation(
            status = decision.status,
            authorized = decision.result.authorized,
            reason = decision.result.reason,
            decidedBy = engineDecision?.ref()?.toDecisionRef(),
            layers = layers,
            overriddenAllows = overriddenAllows,
            rateLimitRules = emptyList(),
            warnings = warnings,
        )
    }

    private fun Decision.ref(): StatementRef? = when (this) {
        is Decision.Permit -> by
        is Decision.Deny -> by
        is Decision.Indeterminate -> null
    }

    /** The allow of an earlier layer that an explicit deny overrode: the engine keeps the first one only. */
    private fun overridden(decision: Decision, trace: Trace): List<DecisionRef> {
        val deny = (decision as? Decision.Deny)?.by ?: return emptyList()
        val deciding = when (deny) {
            is StatementRef.PolicyStatement -> deny.layer
            is StatementRef.RolePermission -> LayerKind.ROLE
            StatementRef.Blacklist, StatementRef.Root -> return emptyList()
        }
        val allow = trace.layers.firstOrNull {
            it.kind < deciding && it.cause == null && it.matchedEffect == EngineEffect.ALLOW
        }
        return listOfNotNull(allow?.matched?.toDecisionRef())
    }

    private class Mapper(
        private val decision: SecurityDecision,
        private val policies: PolicySet,
        private val detail: ExplainDetail,
        private val warnings: MutableList<ExplainWarning>
    ) {
        fun layers(trace: Trace): List<LayerTrace> {
            val layers = ArrayList<LayerTrace>(ExplainLayer.entries.size)
            if (trace.blocked) {
                layers += LayerTrace(
                    ExplainLayer.BLACKLIST,
                    LayerState.EVALUATED,
                    VerifyResult.EXPLICIT_DENY,
                    DecisionRef(ExplainLayer.BLACKLIST),
                )
                layers += LayerTrace(ExplainLayer.ROOT, LayerState.NOT_EVALUATED)
            } else {
                layers += LayerTrace(ExplainLayer.BLACKLIST, LayerState.EVALUATED, VerifyResult.ALLOW)
                layers += if (trace.root) {
                    LayerTrace(ExplainLayer.ROOT, LayerState.EVALUATED, VerifyResult.ALLOW, DecisionRef(ExplainLayer.ROOT))
                } else {
                    LayerTrace(ExplainLayer.ROOT, LayerState.EVALUATED, VerifyResult.IMPLICIT_DENY)
                }
            }
            // Whether an earlier layer ended the evaluation: the engine records a layer without groups the same way
            // before and after the decision.
            var decided = false
            for (kind in LayerKind.entries) {
                val layer = trace.layers.firstOrNull { it.kind == kind }
                layers += if (layer == null) LayerTrace(kind.toExplainLayer(), LayerState.NOT_EVALUATED) else layer(layer, decided)
                decided = decided || layer != null && (layer.cause != null || layer.matchedEffect == EngineEffect.DENY)
            }
            return layers
        }

        private fun layer(layer: EngineLayerTrace, afterDecision: Boolean): LayerTrace {
            val explainLayer = layer.kind.toExplainLayer()
            return when (layer.state) {
                EngineLayerState.NOT_EVALUATED -> LayerTrace(explainLayer, LayerState.NOT_EVALUATED)
                EngineLayerState.ABSENT -> {
                    layer.absentReason?.let { absent(it) }
                    LayerTrace(explainLayer, LayerState.EMPTY)
                }

                EngineLayerState.UNAVAILABLE -> throw checkNotNull(layer.cause).error
                EngineLayerState.MISSING_POLICIES -> {
                    (layer.cause as Cause.MissingPolicies).policyIds.forEach { missing ->
                        warn(
                            ExplainWarningCode.MISSING_POLICY,
                            "The principal lists policy [$missing], which the repository does not have; " +
                                "authorization fails closed.",
                        )
                    }
                    // The engine stops at the missing policies before any of the layer's conditions.
                    LayerTrace(
                        explainLayer,
                        if (afterDecision) LayerState.EVALUATED_AFTER_DECISION else LayerState.EVALUATED
                    )
                }

                EngineLayerState.EVALUATED, EngineLayerState.EVALUATED_AFTER_DECISION -> evaluated(explainLayer, layer)
            }
        }

        private fun evaluated(explainLayer: ExplainLayer, layer: EngineLayerTrace): LayerTrace {
            if (layer.groups.isEmpty()) {
                return LayerTrace(explainLayer, LayerState.EMPTY)
            }
            val state = if (layer.state == EngineLayerState.EVALUATED_AFTER_DECISION) {
                LayerState.EVALUATED_AFTER_DECISION
            } else {
                LayerState.EVALUATED
            }
            // An indeterminate layer has no result: the error ended it, whatever matched in it.
            val result = when {
                layer.cause != null -> null
                layer.matchedEffect == EngineEffect.DENY -> VerifyResult.EXPLICIT_DENY
                layer.matchedEffect == EngineEffect.ALLOW -> VerifyResult.ALLOW
                else -> VerifyResult.IMPLICIT_DENY
            }
            val matched = if (layer.cause == null) layer.matched?.toDecisionRef() else null
            val groups = (policies.layers.first { it.kind == layer.kind } as LayerInput.Loaded).groups
            check(groups.size == layer.groups.size) { "The trace of layer ${layer.kind} does not match its groups." }
            if (layer.kind == LayerKind.ROLE) {
                val group = layer.groups.single()
                val appPermissionGroup = groups.single() as AppPermissionGroup
                check(appPermissionGroup.id == group.id) { "The trace of app [${group.id}] does not match its group." }
                return LayerTrace(
                    layer = explainLayer,
                    state = state,
                    result = result,
                    matched = matched,
                    conditionMatched = group.conditionMatched("app [${group.id}] condition"),
                    roles = roles(group, appPermissionGroup),
                )
            }
            val traces = layer.groups.mapIndexed { index, group ->
                val policy = (groups[index] as PolicyGroup).policy
                check(policy.id == group.id) { "The trace of policy [${group.id}] does not match its group." }
                PolicyTrace(
                    id = group.id,
                    name = policy.name,
                    conditionMatched = group.conditionMatched("policy [${group.id}] condition"),
                    condition = policy.condition.toRef(),
                    statements = group.rules.map { rule ->
                        val ref = rule.ref as StatementRef.PolicyStatement
                        val statement = policy.statements[ref.index]
                        statement(rule, ref.index, null, ref.name, statement.action, statement.condition) {
                            "policy [${group.id}] statement [${ref.index}]"
                        }
                    }.also { group.contractError() },
                )
            }
            return LayerTrace(explainLayer, state, result, matched, policies = traces)
        }

        /**
         * One [RoleTrace] per role grant, in grant order, so a role whose grants visit nothing is listed too. Each
         * statement belongs to the role its trace ref names. A statement's index is its position among the role's
         * granted permissions the app defines, as the narration counts it; an unresolved grant is not a statement,
         * and the evaluation failing on one is a [ExplainWarningCode.MISSING_PERMISSION].
         */
        private fun roles(group: GroupTrace, appPermissionGroup: AppPermissionGroup): List<RoleTrace> {
            val byRole = group.rules.groupBy { (it.ref as StatementRef.RolePermission).roleId }
            val roleIds = appPermissionGroup.rolePermissions.map { it.id }
            check(roleIds.containsAll(byRole.keys)) { "The trace of app [${group.id}] names roles it does not grant." }
            group.contractError()
            return roleIds.map { roleId -> role(appPermissionGroup.appPermission, roleId, byRole[roleId].orEmpty()) }
        }

        private fun role(appPermission: AppPermission, roleId: String, rules: List<RuleTrace>): RoleTrace {
            // Only where the evaluation failed on it: not after a deny matched, nor in a layer after the decision.
            if (rules.any { it.outcome == RuleOutcome.UNRESOLVED && !it.afterDecision }) {
                val missing = rules.filter { it.effect == null }.map { (it.ref as StatementRef.RolePermission).permissionId }
                warn(
                    ExplainWarningCode.MISSING_PERMISSION,
                    "Role [$roleId] is granted permissions $missing, which app [${appPermission.id}] does not define; " +
                        "authorization fails closed.",
                )
            }
            val permissions = rules.filter { it.effect != null }.mapIndexed { index, rule ->
                val ref = rule.ref as StatementRef.RolePermission
                val permission = appPermission.permissionIndexer.getValue(ref.permissionId)
                statement(rule, index, ref.permissionId, ref.name, permission.action, permission.condition) {
                    "role [$roleId] permission [${ref.permissionId}]"
                }
            }
            return RoleTrace(roleId, permissions)
        }

        private fun statement(
            rule: RuleTrace,
            index: Int,
            id: String?,
            name: String,
            action: RequestMatcher,
            condition: RequestMatcher,
            where: () -> String
        ): StatementTrace {
            val error = rule.error
            if (rule.outcome == RuleOutcome.ERROR && error != null) {
                warn(ExplainWarningCode.MATCHER_ERROR, "Matching ${where()} failed: $error")
            }
            return StatementTrace(
                index = index,
                id = id,
                name = name,
                effect = checkNotNull(rule.effect).toApi(),
                outcome = when (rule.outcome) {
                    RuleOutcome.MATCHED -> StatementOutcome.MATCHED
                    RuleOutcome.ACTION_MISS -> StatementOutcome.ACTION_MISS
                    RuleOutcome.CONDITION_MISS -> StatementOutcome.CONDITION_MISS
                    RuleOutcome.NOT_EVALUATED -> StatementOutcome.NOT_EVALUATED
                    RuleOutcome.ERROR -> StatementOutcome.ERROR
                    RuleOutcome.UNRESOLVED -> error("An unresolved grant is not a statement.")
                },
                action = action.toRef(),
                condition = condition.toRef(),
                error = error?.toString(),
            )
        }

        private fun GroupTrace.conditionMatched(where: String): Boolean? = when (guard) {
            GuardOutcome.MATCHED -> true
            GuardOutcome.MISSED -> false
            GuardOutcome.ERROR -> {
                warn(ExplainWarningCode.MATCHER_ERROR, "Matching $where failed: $guardError")
                null
            }
        }

        /** The engine could not record all of the group: say so rather than show an incomplete group as complete. */
        private fun GroupTrace.contractError() {
            contractError?.let {
                warn(ExplainWarningCode.MATCHER_ERROR, "Tracing [$id] failed, so its trace is incomplete: $it")
            }
        }

        private fun absent(reason: AbsentReason) {
            val request = decision.request
            val principalRoles = decision.securityContext?.principal?.roles.orEmpty()
            when (reason) {
                AbsentReason.NO_ROLE_IN_SPACE -> warn(
                    ExplainWarningCode.NO_ROLE_IN_SPACE,
                    "The principal has roles $principalRoles, but none applies in space [${request?.spaceId}]: " +
                        "a role applies in a space only when written role@space, and in the default space " +
                        "only without a suffix.",
                )

                AbsentReason.NO_APP_PERMISSION -> {
                    val spaceId = request?.spaceId.orEmpty()
                    warn(
                        ExplainWarningCode.NO_APP_PERMISSION,
                        "The principal has roles ${RoleScope.rolesIn(spaceId, principalRoles)} in space [$spaceId], " +
                            "but app [${request?.appId}] has no app permission, so none of its role grants apply.",
                    )
                }

                AbsentReason.NO_POLICIES, AbsentReason.NO_ROLES -> Unit
            }
        }

        private fun warn(code: ExplainWarningCode, message: String) {
            warnings += ExplainWarning(code, message)
        }

        private fun RequestMatcher.toRef(): MatcherRef =
            MatcherRef(type, configuration.takeIf { detail == ExplainDetail.FULL })
    }

    private fun EngineEffect.toApi(): Effect = when (this) {
        EngineEffect.ALLOW -> Effect.ALLOW
        EngineEffect.DENY -> Effect.DENY
    }
}
