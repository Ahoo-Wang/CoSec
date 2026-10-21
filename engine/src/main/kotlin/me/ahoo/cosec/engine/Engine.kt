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
import me.ahoo.cosec.model.Decision

/**
 * The authorization decision as a synchronous function without I/O (RFC 0002).
 */
interface Engine {
    /**
     * Decides [request] against [policies]. The decision never depends on [trace].
     *
     * An [Exception] thrown by a guard or rule becomes [Decision.Indeterminate]; an [Error] propagates.
     */
    fun evaluate(request: AuthzRequest, policies: PolicySet, trace: TraceLevel = TraceLevel.OFF): Evaluation

    /**
     * Whether deciding [request] needs policy data. False for a blocked or root subject, so the caller skips the
     * repositories and passes [PolicySet.NOT_LOADED].
     */
    fun requiresPolicies(request: AuthzRequest): Boolean
}

/** [trace] is null at [TraceLevel.OFF]. */
class Evaluation(val decision: Decision, val trace: Trace?)
