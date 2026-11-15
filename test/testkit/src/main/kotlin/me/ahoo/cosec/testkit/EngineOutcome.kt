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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.ScopeResolution

/** What evaluating an engine scenario produced, compared with its `expect` by [EngineExpectations]. */
@ExperimentalCoSecApi
sealed interface EngineOutcome {
    /** The engine decided. */
    class Decided(val decision: Decision) : EngineOutcome

    /** `ScopeResolver` rejected the request before the engine. */
    class Rejected(val reason: ScopeResolution.Rejected.Reason) : EngineOutcome

    /** A document does not compile; the request was never evaluated. */
    class NotCompiled(val error: String) : EngineOutcome

    /**
     * Enforcement refuses the request before any decision (`400 invalid-request`, `PIP-15`): [refused] names the part
     * of the scenario's request it refuses, as the scenario names it.
     */
    class InvalidRequest(val refused: RequestPart) : EngineOutcome

    /** A part of a scenario's request, rendered in lower case in `expect` (`"refused": "action"`). */
    enum class RequestPart {
        /** An `http` action whose method holds a lower-case letter (`PIP-18`). */
        ACTION,

        /**
         * A resource enforcement refuses: an `http` path the pipeline answers 400 for, such as one with a dot segment
         * (`PIP-14`), or a typed resource id beyond the bounds of a request path.
         */
        RESOURCE,
    }

    /**
     * This outcome in the scenario syntax, as `expect` writes it: `{"decision":"Deny","by":null}`, for an assertion
     * message or a log line.
     */
    fun describe(): String = EngineExpectations.render(this).toString()
}
