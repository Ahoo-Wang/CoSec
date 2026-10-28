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

import me.ahoo.cosec.testkit.EngineOutcome
import me.ahoo.cosec.testkit.EngineScenarios

/**
 * The `engine` runner (RFC 0008 §3.3). The format and its evaluation are the test kit's [EngineScenarios], which users
 * run against their own documents; this adds the suite's header and documents.
 */
class EngineRunner(private val scenarios: EngineScenarios = EngineScenarios()) : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome = when (val outcome = scenarios.run(scenario.node, HEADER)) {
        is EngineOutcome.Decided -> Outcome.Decided(outcome.decision)
        is EngineOutcome.Rejected -> Outcome.Rejected(outcome.reason)
        is EngineOutcome.NotCompiled -> Outcome.NotCompiled(outcome.error)
    }

    private companion object {
        val HEADER = setOf("\$comment", "rules", "polarity", "level", "description")
    }
}
