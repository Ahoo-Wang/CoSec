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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.trace.EvaluationTrace
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.Scope

/**
 * The authorization decision over bindings (RFC 0004 §6): a synchronous function without I/O.
 */
@InternalCoSecApi
interface Engine {
    /**
     * Decides [request] against [snapshot]. The decision never depends on [trace], nor on the order of the candidates.
     *
     * An [Exception] thrown while matching a statement (a path that does not decode, a host attribute read that breaks
     * the [ConditionInput] contract) is an error of that statement; an [Error] propagates. Throws
     * [IllegalArgumentException] when [snapshot] holds an app or tenant document of another scope than the request's.
     */
    fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel = TraceLevel.OFF): Evaluation

    /**
     * The external attributes evaluating [request] against [snapshot] may read (RFC 0006 §5.4.2): what the pipeline
     * fetches before [evaluate]. Pure, like [evaluate]; [EngineRequest.external] is not read. Empty when no document of
     * [snapshot] reads an external attribute.
     */
    fun plan(request: EngineRequest, snapshot: SnapshotView): AttributePlan = CoSecEngine.plan(request, snapshot)
}

/**
 * What one evaluation over bindings reads besides the snapshot.
 *
 * [request] holds the action, the resource, the environment and the principal's id, attributes and whether it is
 * authenticated. The roles and attachments are in [scope] and [memberships]. The tenant comes from [scope] only, which
 * is the subject's for an authenticated principal and the default tenant for an anonymous one.
 *
 * Conditions read the request's attributes (RFC 0005 §3.2). The engine answers those it holds itself from these
 * fields; [attributes] answers the rest (`http.*`, `request.attributes.*`) and is never asked for the others.
 */
@InternalCoSecApi
class EngineRequest(
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

/** [trace] is null at [TraceLevel.OFF]. */
@InternalCoSecApi
class Evaluation(val decision: Decision, val trace: EvaluationTrace?)
