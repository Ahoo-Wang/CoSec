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

import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.model.condition.Containment
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.ScopeResolution
import tools.jackson.databind.JsonNode

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

    /** The pipeline answered: [status], whether the request goes on, and the problem's slug when it does not. */
    /** [identity]: whether the outcome forwards `CoSec-Identity` at all, and what (null: the header is stripped). */
    class Answered(
        val status: Int,
        val proceed: Boolean,
        val type: String?,
        val identity: Pair<Boolean, JsonNode?> = false to null,
    ) : Outcome

    /** The caller went away and the pipeline made no outcome at all (RFC 0006 §7.4). */
    data object Cancelled : Outcome

    /** A document of the scenario does not compile; the request was never evaluated. */
    class NotCompiled(val error: String) : Outcome

    /** An analysis scenario: what the analysis said, and how the condition(s) evaluated on the request. */
    class Analyzed(
        val keys: IndexKeys?,
        val admits: Boolean?,
        val implies: Containment?,
        val evaluations: Map<String, Boolean>,
    ) : Outcome

    /** A control scenario of the grant analysis: the verdict for each fragment, and the protected entries changed. */
    class Granted(val analysis: GrantAnalysis) : Outcome

    /** A control scenario of the change flow: what each step left its change in, every change's errors, the heads. */
    class Changed(
        val steps: List<String>,
        val errors: Map<String, List<String>>,
        val heads: Map<String, Long>,
        /** The reasons of each change's escalations (RFC 0007 §7.5), for the changes that have any. */
        val escalations: Map<String, List<String>> = emptyMap(),
    ) : Outcome

    /** A control scenario of publishing: each publish's answer, the head afterwards, the records and the write order. */
    class Stored(
        val outcomes: List<String>,
        val revision: Long?,
        val parent: Long?,
        val parentRecord: Boolean?,
        val records: Int,
        val writes: List<String>,
    ) : Outcome
}
