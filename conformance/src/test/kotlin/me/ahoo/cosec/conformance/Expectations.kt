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

import me.ahoo.cosec.control.grant.GrantFragment
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.ProtectedEntry
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.testkit.EngineExpectations
import me.ahoo.cosec.testkit.EngineOutcome
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.textOrNull
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

/**
 * Compares an [Outcome] with the scenario's `expect`. Outcomes are rendered in the scenario syntax and compared as JSON,
 * exactly: an `expect` says everything about the decision, including the deciding statement (`by`) and the cause. The
 * engine's outcomes are rendered and compared by the test kit's [EngineExpectations], the others here:
 *
 * ```jsonc
 * { "keys": ["a"], "admits": true, "implies": "YES", "evaluations": { "c1": true } }   // analysis
 * { "status": 200, "proceed": true }                                   // pipeline
 * { "status": 200, "proceed": true, "identity": { "sub": "alice", ... } } // pipeline of a gateway: what it forwards
 * { "status": 400, "type": "invalid-request" }                         // pipeline, the problem's slug
 * { "outcome": "none" }                                                // pipeline, the caller cancelled
 * { "fragments": [ { "policy": "p", "statement": 0, "action": "a:b", "resource": "r", "verdict": "YES" } ],
 *   "unprotect": ["policy:p"] }                                                          // control: grant analysis
 * { "outcomes": ["Published"], "head": { "revision": 1, "parent": null, "parentRecord": null }, "records": 1,
 *   "writes": ["record", "head"] }                                                       // control: publishing
 * ```
 */
object Expectations {
    private val json = JsonNodeFactory.instance

    fun check(scenario: Scenario, outcome: Outcome) {
        val expected = scenario.node.required("expect")
        engine(outcome)?.let { return EngineExpectations.check(scenario.name, expected, it, exact = true) }
        if (expected.get("decision")?.textOrNull() == "NotCompiled") {
            fail("$scenario: expected $expected, but the documents compile: ${render(outcome)}")
        }
        val actual = render(outcome)
        if (actual != expected) {
            fail("$scenario:\n  expected $expected\n  actual   $actual")
        }
    }

    /** The outcomes the engine level shares with the test kit. */
    private fun engine(outcome: Outcome): EngineOutcome? = when (outcome) {
        is Outcome.Decided -> EngineOutcome.Decided(outcome.decision)
        is Outcome.Rejected -> EngineOutcome.Rejected(outcome.reason)
        is Outcome.NotCompiled -> EngineOutcome.NotCompiled(outcome.error)
        else -> null
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    fun render(outcome: Outcome): JsonNode = when (outcome) {
        is Outcome.Decided -> EngineExpectations.render(outcome.decision)
        is Outcome.Rejected -> EngineExpectations.render(EngineOutcome.Rejected(outcome.reason))
        is Outcome.NotCompiled -> EngineExpectations.render(EngineOutcome.NotCompiled(outcome.error))
        is Outcome.Answered -> answered(outcome)

        Outcome.Cancelled -> json.objectNode().put("outcome", "none")
        is Outcome.Analyzed -> analyzed(outcome)
        is Outcome.Granted, is Outcome.Stored, is Outcome.Changed -> control(outcome)
    }

    private fun changed(outcome: Outcome.Changed): JsonNode = json.objectNode().apply {
        set("steps", json.arrayNode().apply { outcome.steps.forEach(::add) })
        set(
            "errors",
            json.objectNode().apply {
                outcome.errors.forEach { (id, checks) ->
                    set(
                        id,
                        json.arrayNode().apply { checks.forEach(::add) }
                    )
                }
            }
        )
        set(
            "heads",
            json.objectNode().apply { outcome.heads.forEach { (key, revision) -> put(key, Math.toIntExact(revision)) } }
        )
        if (outcome.escalations.isNotEmpty()) {
            set(
                "escalations",
                json.objectNode().apply {
                    outcome.escalations.forEach { (id, reasons) ->
                        set(
                            id,
                            json.arrayNode().apply { reasons.forEach(::add) }
                        )
                    }
                }
            )
        }
    }

    /** The control plane's outcomes: grant analysis, publishing, the change flow. */
    private fun control(outcome: Outcome): JsonNode = when (outcome) {
        is Outcome.Granted -> granted(outcome)
        is Outcome.Stored -> stored(outcome)
        is Outcome.Changed -> changed(outcome)
        else -> error("Not a control outcome: $outcome")
    }

    private fun stored(outcome: Outcome.Stored): JsonNode = json.objectNode().apply {
        set("outcomes", json.arrayNode().apply { outcome.outcomes.forEach(::add) })
        set(
            "head",
            json.objectNode().apply {
                // As ints, as the scenario's JSON reads small numbers: an IntNode never equals a LongNode.
                if (outcome.revision == null) {
                    putNull(
                        "revision"
                    )
                } else {
                    put("revision", Math.toIntExact(outcome.revision))
                }
                if (outcome.parent == null) putNull("parent") else put("parent", Math.toIntExact(outcome.parent))
                if (outcome.parentRecord == null) putNull("parentRecord") else put("parentRecord", outcome.parentRecord)
            },
        )
        put("records", outcome.records)
        set("writes", json.arrayNode().apply { outcome.writes.forEach(::add) })
    }

    private fun analyzed(outcome: Outcome.Analyzed): JsonNode = json.objectNode().apply {
        outcome.keys?.let { set("keys", keys(it)) }
        outcome.admits?.let { put("admits", it) }
        outcome.implies?.let { put("implies", it.name) }
        if (outcome.evaluations.isNotEmpty()) {
            set("evaluations", json.objectNode().apply { outcome.evaluations.forEach { (k, v) -> put(k, v) } })
        }
    }

    private fun granted(outcome: Outcome.Granted): JsonNode = json.objectNode().apply {
        set("fragments", json.arrayNode().apply { outcome.analysis.verdicts.forEach { (f, v) -> add(fragment(f, v)) } })
        if (outcome.analysis.unprotect.isNotEmpty()) {
            set("unprotect", json.arrayNode().apply { outcome.analysis.unprotect.forEach { add(entry(it)) } })
        }
    }

    /** A fragment and its verdict: `YES` for contained (also for the trust root), `UNKNOWN` with reason and shortfall. */
    private fun fragment(fragment: GrantFragment, verdict: GrantVerdict): JsonNode = json.objectNode().apply {
        put("policy", fragment.policy.value)
        put("statement", fragment.statement)
        put("action", fragment.action.toString())
        put("resource", fragment.resource.toString())
        when (verdict) {
            GrantVerdict.TrustRoot -> put("verdict", "YES").put("trustRoot", true)
            is GrantVerdict.Contained -> put("verdict", "YES")
            is GrantVerdict.Unknown -> {
                put("verdict", "UNKNOWN")
                put("reason", verdict.reason.name)
                verdict.shortfall?.let { put("shortfall", it.name) }
            }
        }
    }

    private fun answered(outcome: Outcome.Answered): JsonNode = json.objectNode().put("status", outcome.status).apply {
        if (outcome.proceed) put("proceed", true)
        outcome.type?.let { put("type", it) }
        if (outcome.identity.first) set("identity", outcome.identity.second ?: json.nullNode())
    }

    private fun entry(entry: ProtectedEntry): String = when (entry) {
        is ProtectedEntry.Policy -> "policy:${entry.id.value}"
        is ProtectedEntry.Binding -> "binding:${entry.id.value}"
    }

    fun render(decision: Decision): JsonNode = EngineExpectations.render(decision)

    private fun keys(keys: IndexKeys): JsonNode = when (keys) {
        IndexKeys.Unconstrained -> json.stringNode("any")
        is IndexKeys.Only -> json.arrayNode().apply { keys.values.sorted().forEach(::add) }
    }
}
