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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.LayerKind

/*
 * The engine input of RFC 0002 stage 2. These are CoSec's internal adapter contracts: a policy snapshot replaces them
 * (RFC 0001 stages 3–4). Do not implement them in applications; extend CoSec through `ActionMatcherFactory` and
 * `ConditionMatcherFactory`.
 */

/**
 * The layers to evaluate, in the order [LayerKind] declares: global, principal, role.
 * A set may leave layers out, for example to evaluate a single policy, but never reorders or repeats them.
 *
 * [layers] is used as given, not copied: a copy would allocate on every request. The caller must not change it, or any
 * [LayerInput.Loaded.groups], while the set may still be evaluated.
 */
class PolicySet(val layers: List<LayerInput>) {
    init {
        for (i in 1 until layers.size) {
            require(layers[i - 1].kind < layers[i].kind) {
                "Layers must be in the order GLOBAL, PRINCIPAL, ROLE without repeats: ${layers.map { it.kind }}"
            }
        }
    }

    companion object {
        /** For a request that [Engine.requiresPolicies] says needs no policies. */
        @JvmField
        val NOT_LOADED: PolicySet = PolicySet(emptyList())
    }
}

sealed interface LayerInput {
    val kind: LayerKind

    /** Nothing to evaluate in this layer. [reason] only shows in the trace. */
    data class Absent(override val kind: LayerKind, val reason: AbsentReason) : LayerInput

    /** Reading this layer failed. An evaluation that reaches it is [me.ahoo.cosec.model.Decision.Indeterminate]. */
    data class Unavailable(override val kind: LayerKind, val error: Throwable) : LayerInput

    /**
     * The layer's groups, in source order.
     *
     * When [missing] is set, an evaluation that reaches this layer is [me.ahoo.cosec.model.Decision.Indeterminate]
     * before any guard runs: a missing policy may be the deny that keeps the request out (D2). A role granting a
     * permission its app does not define is an [UnresolvedGrant] among the rules instead, where the grant is.
     *
     * [groups] is used as given, not copied; the caller must not change it while the layer may still be evaluated.
     */
    class Loaded(
        override val kind: LayerKind,
        val groups: List<RuleGroup>,
        val missing: Cause.MissingPolicies? = null,
    ) : LayerInput
}

enum class AbsentReason { NO_POLICIES, NO_ROLES, NO_ROLE_IN_SPACE, NO_APP_PERMISSION }

/** A policy (global and principal layers) or an app's permissions (role layer). */
interface RuleGroup {
    /** The policy id or app id. */
    val id: String

    /** The policy condition or app condition. Every guard of a layer runs before any of its rules. */
    val guard: Guard

    /**
     * Visits the entries in source order until [visitor] returns true, and returns whether it did.
     *
     * Must visit the same rules in the same order every time, and must not allocate a collection to do it: the role
     * layer's rules are roles × granted permissions.
     */
    fun forEachRule(visitor: RuleVisitor): Boolean
}

fun interface RuleVisitor {
    /** [grantedVia] is the id of the role that grants the entry in the role layer, and null elsewhere. Return true to stop. */
    fun visit(entry: RuleEntry, grantedVia: String?): Boolean
}

/** A position in a group's rule sequence: a [Rule], or in the role layer an [UnresolvedGrant]. */
sealed interface RuleEntry

/** May throw an [Exception], which makes the evaluation [me.ahoo.cosec.model.Decision.Indeterminate]. */
interface Guard {
    fun matches(request: AuthzRequest): Boolean
}

interface Rule : RuleEntry {
    val effect: Effect

    /** The position in its policy, or in its role's grant list. */
    val index: Int
    val name: String

    /** The permission id in the role layer; null for policy statements. */
    val id: String?

    /** May throw an [Exception], which makes the evaluation [me.ahoo.cosec.model.Decision.Indeterminate]. */
    fun match(request: AuthzRequest): RuleMatch
}

enum class RuleMatch { MATCHED, ACTION_MISS, CONDITION_MISS }

/**
 * A role grants [permissionId], which its app does not define (S10a). Plain data at the grant's position: when the
 * deny pass of a layer whose app condition matched reaches it, before any DENY has matched, the evaluation is
 * [me.ahoo.cosec.model.Decision.Indeterminate] with [Cause.MissingPermissions], as 7.0 fails there. A layer whose
 * condition missed, or whose deny pass matched a DENY first, never fails on it.
 *
 * [error] is the exception the adapter rethrows (`MissingPermissionException`), so status codes stay as they are.
 * Only the role layer has unresolved grants.
 */
class UnresolvedGrant(val permissionId: String, val error: Throwable) : RuleEntry
