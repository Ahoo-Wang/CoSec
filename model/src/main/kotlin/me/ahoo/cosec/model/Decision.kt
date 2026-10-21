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

package me.ahoo.cosec.model

/** The rule that made a decision. */
sealed interface StatementRef {
    data object Blacklist : StatementRef

    data object Root : StatementRef

    /** A statement of a global or principal policy; [index] is its position in the policy. */
    data class PolicyStatement(
        val layer: LayerKind,
        val policyId: String,
        val index: Int,
        val name: String
    ) : StatementRef

    /** A permission of [appId] granted through [roleId]. */
    data class RolePermission(val appId: String, val roleId: String, val permissionId: String, val name: String) :
        StatementRef
}

sealed interface Decision {
    data class Permit(val by: StatementRef) : Decision

    /** [by] is null for an implicit deny: nothing matched. */
    data class Deny(val by: StatementRef?) : Decision

    /** No decision could be made. Callers deny. */
    data class Indeterminate(val cause: Cause) : Decision
}

sealed interface Cause {
    /** The original exception. The adapter rethrows it unchanged, so status codes stay as they are (RFC 0002 §3.5). */
    val error: Throwable

    /** A policy the principal references is not in the repository (D2). */
    data class MissingPolicies(val policyIds: Set<String>, override val error: Throwable) : Cause

    /**
     * A role grants a permission id its app does not define (S10a, 7.0). Raised where the deny pass reaches the grant
     * [at], whose `permissionId` is the missing id; [error] is the exception the adapter built for it.
     */
    data class MissingPermissions(val at: StatementRef.RolePermission, override val error: Throwable) : Cause

    /** Reading a layer's data failed, for example a Redis timeout or a strict cache read failure. */
    data class SourceUnavailable(val layer: LayerKind, override val error: Throwable) : Cause

    /**
     * A matcher threw. [at] is null when the group's guard (policy or app condition) threw; [groupId] says which group.
     */
    data class MatcherFailed(
        val layer: LayerKind,
        val groupId: String,
        val at: StatementRef?,
        override val error: Throwable,
    ) : Cause
}
