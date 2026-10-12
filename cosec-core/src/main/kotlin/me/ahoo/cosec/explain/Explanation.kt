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

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.pipeline.SecurityStatus

/**
 * How much of each matcher an [Explanation] reveals.
 */
enum class ExplainDetail {
    /** Matcher types only. Safe to show to policy reviewers. */
    SUMMARY,

    /** Matcher types and configurations, which may contain IP ranges, paths and expressions. */
    FULL,
}

/**
 * @param exhaustive also evaluate the layers that authorization skips after an explicit deny
 */
data class ExplainOptions(
    val exhaustive: Boolean = false,
    val detail: ExplainDetail = ExplainDetail.SUMMARY
)

/**
 * Why authorization decided what it did for one request.
 *
 * [status], [authorized], [reason] and [decidedBy] come from running the real authorization, never from the
 * trace; the [layers] narrate how it got there. When the two disagree, [warnings] says so.
 *
 * @param overriddenAllows allows that a later layer's explicit deny overrode
 * @param rateLimitRules ids of the rate limit rules this request would spend a permit from; none is spent
 */
data class Explanation(
    val status: SecurityStatus,
    val authorized: Boolean,
    val reason: String,
    val decidedBy: DecisionRef?,
    val layers: List<LayerTrace>,
    val overriddenAllows: List<DecisionRef>,
    val rateLimitRules: List<String>,
    val warnings: List<ExplainWarning>
)

enum class ExplainLayer {
    ROOT,
    BLACKLIST,
    GLOBAL_POLICY,
    PRINCIPAL_POLICY,
    ROLE_PERMISSION,
}

enum class LayerState {
    /** Authorization evaluated this layer. */
    EVALUATED,

    /** Authorization had already decided; evaluated only because [ExplainOptions.exhaustive] was set. */
    EVALUATED_AFTER_DECISION,

    /** Authorization had already decided and did not evaluate this layer. */
    NOT_EVALUATED,

    /** The layer had nothing to evaluate (no policies, roles or app permission). */
    EMPTY,
}

/**
 * The rule that decided, or that a later deny overrode.
 */
data class DecisionRef(
    val layer: ExplainLayer,
    val policyId: String? = null,
    val statementIndex: Int? = null,
    val statementName: String? = null,
    val roleId: String? = null,
    val permissionId: String? = null
)

/**
 * @param conditionMatched the app permission's condition, for [ExplainLayer.ROLE_PERMISSION]
 * @param matched the decisive match of this layer on its own, null when nothing matched
 */
data class LayerTrace(
    val layer: ExplainLayer,
    val state: LayerState,
    val result: VerifyResult? = null,
    val matched: DecisionRef? = null,
    val conditionMatched: Boolean? = null,
    val policies: List<PolicyTrace> = emptyList(),
    val roles: List<RoleTrace> = emptyList()
)

data class PolicyTrace(
    val id: String,
    val name: String,
    val conditionMatched: Boolean?,
    val condition: MatcherRef,
    val statements: List<StatementTrace>
)

data class RoleTrace(
    val roleId: String,
    val permissions: List<StatementTrace>
)

enum class StatementOutcome {
    MATCHED,
    ACTION_MISS,
    CONDITION_MISS,

    /** Skipped because its policy's or app permission's condition did not match. */
    NOT_EVALUATED,
    ERROR,
}

/**
 * A policy statement, or a role permission (then [id] is the permission id).
 */
data class StatementTrace(
    val index: Int,
    val id: String?,
    val name: String,
    val effect: Effect,
    val outcome: StatementOutcome,
    val action: MatcherRef,
    val condition: MatcherRef,
    val error: String? = null
)

/**
 * @param configuration present only with [ExplainDetail.FULL]
 */
data class MatcherRef(
    val type: String,
    val configuration: Configuration? = null
)

enum class ExplainWarningCode {
    /** The principal lists a policy id the repository does not have. */
    MISSING_POLICY,

    /** The principal has roles, but the request's app and space have no app permission. */
    NO_APP_PERMISSION,

    /** A matcher threw; authorization fails closed on it. */
    MATCHER_ERROR,

    /** The trace disagrees with the real decision. Trust the decision; please report this. */
    TRACE_INCONSISTENT,
}

data class ExplainWarning(
    val code: ExplainWarningCode,
    val message: String
)
