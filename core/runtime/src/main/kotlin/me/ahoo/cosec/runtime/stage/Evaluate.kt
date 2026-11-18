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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Outcome

/**
 * Stage 7: the engine's decision. It does not suspend. Every use of break-glass is written to a REQUIRED audit (RFC
 * 0006 §9.4.3, Q20): without a REQUIRED listener, a break-glass permit is a 503; with one, `DeliverRequired` lets it
 * proceed only when they all succeed.
 */
internal class Evaluate(
    private val engine: Engine,
    private val traceLevel: TraceLevel,
    private val listeners: DecisionListeners,
) : Stage {
    override suspend fun run(state: RequestState): Outcome? {
        val evaluation = engine.evaluate(state.request, state.view, traceLevel)
        val decision = evaluation.decision
        state.decision = decision
        state.evaluationTrace = evaluation.trace
        if (decision !is Decision.Permit || !state.view.platform.isBreakGlass(decision.by.via)) {
            return null
        }
        if (listeners.required.isEmpty()) {
            requestPathLog.error {
                "Request [${state.requestId}] permitted through break-glass, but no REQUIRED listener is registered."
            }
            return state.unavailable(Cause.RequiredListenerFailed(emptyList()))
        }
        state.breakGlass = true
        return null
    }
}
