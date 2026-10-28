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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.Scope

/**
 * The authorization decision over bindings (RFC 0004 §6): a synchronous function without I/O.
 *
 * It sits next to the layered [me.ahoo.cosec.engine.Engine] until the switch (RFC 0004 PR-7), and becomes its
 * `evaluate(request, snapshot)` when the layers are removed (PR-8).
 */
interface FlatEngine {
    /**
     * Decides [request] against [snapshot]. The decision never depends on [trace], nor on the order of the candidates.
     *
     * An [Exception] thrown while matching a statement (a path that does not decode, a host attribute read that breaks
     * the [ConditionInput] contract) is an error of that statement; an [Error] propagates. Throws
     * [IllegalArgumentException] when [snapshot] holds an app or tenant document of another scope than the request's.
     */
    fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel = TraceLevel.OFF): FlatEvaluation

    /**
     * The external attributes evaluating [request] against [snapshot] may read (RFC 0006 §5.4.2): what the pipeline
     * fetches before [evaluate]. Pure, like [evaluate]; [FlatRequest.external] is not read. Empty when no document of
     * [snapshot] reads an external attribute.
     */
    fun plan(request: FlatRequest, snapshot: SnapshotView): AttributePlan = CoSecFlatEngine.plan(request, snapshot)
}

/**
 * What one evaluation over bindings reads besides the snapshot.
 *
 * Until the request model changes (RFC 0006), [request] holds the action, the resource, the environment and the
 * principal's id, attributes and whether it is authenticated. The rest of its subject (tenant, roles, policies, root)
 * is not read: [scope] and [memberships] replace it. The tenant comes from [scope] only, which is the subject's for
 * an authenticated principal and the default tenant for an anonymous one.
 *
 * Conditions read the request's attributes (RFC 0005 §3.2). The engine answers those it holds itself from these
 * fields; [attributes] answers the rest (`http.*`, `request.attributes.*`) and is never asked for the others.
 */
class FlatRequest(
    val request: AuthzRequest,
    /** Resolved by `ScopeResolver`: its tenant is the principal's, its roles those in the request's space. */
    val scope: Scope,
    /** The attachments, for an authenticated principal, and the roles in every space, for `subject.roles`. */
    val memberships: Memberships,
    /** The request time in epoch milliseconds, for binding expiry and `environment.time`. The engine reads no clock. */
    val time: Long,
    /** The host's attributes, read under the [ConditionInput] contract. */
    val attributes: ConditionInput = AbsentAttributes,
    /** The external attributes fetched for the [AttributePlan] (RFC 0006 §5.4); none by default. */
    val external: ExternalValues = ExternalValues.NONE,
)

/** A host that has no attributes of its own: every read is absent. */
object AbsentAttributes : ConditionInput {
    override fun string(attribute: Attribute): String? = null

    override fun bool(attribute: Attribute): Boolean? = null

    override fun stringSet(attribute: Attribute): Set<String>? = null

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
}

/** [trace] is null at [TraceLevel.OFF]. */
class FlatEvaluation(val decision: Decision, val trace: FlatTrace?)

/** The default [FlatEngine]: stateless and thread-safe. */
object CoSecFlatEngine : FlatEngine {
    internal val IMPLICIT_DENY: Decision = Decision.Deny(null)
    private val IMPLICIT_DENY_EVALUATION = FlatEvaluation(IMPLICIT_DENY, null)

    override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
        snapshot.checkScope(request.scope)
        // Two call sites, so the untraced one never shares a type profile with the traced one.
        if (trace == TraceLevel.OFF) {
            return untraced(FlatEvaluator(request, snapshot).evaluate())
        }
        return FlatTracingEvaluator(request, snapshot, trace).evaluateTraced()
    }

    override fun plan(request: FlatRequest, snapshot: SnapshotView): AttributePlan {
        snapshot.checkScope(request.scope)
        if (!snapshot.readsExternal) {
            return AttributePlan.EMPTY
        }
        return FlatPlanner(request, snapshot).plan()
    }

    internal fun untraced(decision: Decision): FlatEvaluation =
        if (decision === IMPLICIT_DENY) IMPLICIT_DENY_EVALUATION else FlatEvaluation(decision, null)
}
