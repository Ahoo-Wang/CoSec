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
import me.ahoo.cosec.model.decision.Decision

/** The default [Engine]: stateless and thread-safe. */
@InternalCoSecApi
object CoSecEngine : Engine {
    internal val IMPLICIT_DENY: Decision = Decision.Deny(null)
    private val IMPLICIT_DENY_EVALUATION = Evaluation(IMPLICIT_DENY, null)

    override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation {
        snapshot.checkScope(request.scope)
        // Two call sites, so the untraced one never shares a type profile with the traced one.
        if (trace == TraceLevel.OFF) {
            return untraced(Evaluator(request, snapshot).evaluate())
        }
        return TracingEvaluator(request, snapshot, trace).evaluateTraced()
    }

    override fun plan(request: EngineRequest, snapshot: SnapshotView): AttributePlan {
        snapshot.checkScope(request.scope)
        if (!snapshot.readsExternal) {
            return AttributePlan.EMPTY
        }
        return AttributePlanner(request, snapshot).plan()
    }

    internal fun untraced(decision: Decision): Evaluation =
        if (decision === IMPLICIT_DENY) IMPLICIT_DENY_EVALUATION else Evaluation(decision, null)
}
