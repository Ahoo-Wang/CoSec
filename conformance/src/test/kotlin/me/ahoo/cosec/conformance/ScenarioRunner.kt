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

package me.ahoo.cosec.conformance

import me.ahoo.cosec.model.condition.Containment
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.ScopeResolution

/**
 * Runs the scenarios of one level (RFC 0008 §3.3). A runner only interprets the scenario: it builds what the
 * scenario describes, calls the code under test and reports what came back. It decides nothing.
 */
interface ScenarioRunner {
    fun run(scenario: Scenario): Outcome
}

/** What running a scenario produced, compared with its `expect` by [Expectations]. */
sealed interface Outcome {
    /** The engine decided. */
    class Decided(val decision: Decision) : Outcome

    /** `ScopeResolver` rejected the request before the engine. */
    class Rejected(val reason: ScopeResolution.Rejected.Reason) : Outcome

    /** A document of the scenario does not compile; the request was never evaluated. */
    class NotCompiled(val error: String) : Outcome

    /** An analysis scenario: what the analysis said, and how the condition(s) evaluated on the request. */
    class Analyzed(
        val keys: IndexKeys?,
        val admits: Boolean?,
        val implies: Containment?,
        val evaluations: Map<String, Boolean>,
    ) : Outcome
}
