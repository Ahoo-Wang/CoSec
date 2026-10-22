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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.PolicySet

/**
 * Sees an evaluation of [me.ahoo.cosec.authorization.EngineAuthorization] with the policy set it evaluated, before
 * the decision is mapped. The trace-based explanation uses it to read the one evaluation that also decided.
 */
internal fun interface EvaluationObserver {
    fun evaluated(evaluation: Evaluation, policies: PolicySet)
}
