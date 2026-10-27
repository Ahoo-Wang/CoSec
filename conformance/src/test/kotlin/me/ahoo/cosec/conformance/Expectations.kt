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
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * Compares an [Outcome] with the scenario's `expect`. Outcomes are rendered in the scenario syntax and compared as JSON,
 * exactly: an `expect` says everything about the decision, including the deciding statement (`by`) and the cause.
 *
 * ```jsonc
 * { "decision": "Permit", "by": { "policy": "p", "statement": 0, "via": { "binding": "b", "document": "platform" } } }
 * { "decision": "Deny", "by": null }                                   // the implicit deny
 * { "decision": "Indeterminate", "cause": { "type": "MissingPolicies", "policies": ["p"] } }
 * { "decision": "Rejected", "reason": "SPACE_NOT_MEMBER" }
 * { "decision": "NotCompiled", "error": "a part of the error message" }
 * { "keys": ["a"], "admits": true, "implies": "YES", "evaluations": { "c1": true } }   // analysis
 * { "status": 200, "proceed": true }                                   // pipeline
 * { "status": 400, "type": "invalid-request" }                         // pipeline, the problem's slug
 * { "outcome": "none" }                                                // pipeline, the caller cancelled
 * { "fragments": [ { "policy": "p", "statement": 0, "action": "a:b", "resource": "r", "verdict": "YES" } ],
 *   "unprotect": ["policy:p"] }                                                          // control: grant analysis
 * { "outcomes": ["Published"], "head": { "revision": 1, "parent": null, "parentRecord": null }, "records": 1,
 *   "writes": ["record", "head"] }                                                       // control: publishing
 * ```
 *
 * `via` is `{ "binding": id, "document": "platform" | "app" | "tenant" }`, `{ "attached": policy }` or `"builtin"`; a
 * statement's `name` appears when it has one. Errors are not compared, only where they happened.
 */
object Expectations {
    private val json = JsonNodeFactory.instance

    fun check(scenario: Scenario, outcome: Outcome) {
        val expected = scenario.node.required("expect")
        val notCompiled = expected.get("decision")?.textOrNull() == "NotCompiled"
        if (outcome is Outcome.NotCompiled || notCompiled) {
            if (outcome !is Outcome.NotCompiled) {
                fail("$scenario: expected $expected, but the documents compile: ${render(outcome)}")
            }
            if (!notCompiled) {
                fail(
                    "$scenario: expected $expected, but the documents do not compile: ${outcome.error}"
                )
            }
            expected.only("decision", "error")
            if (expected.required("error").text() !in outcome.error) {
                fail("$scenario: expected the error to mention $expected, but it is: ${outcome.error}")
            }
            return
        }
        val actual = render(outcome)
        if (actual != expected) {
            fail("$scenario:\n  expected $expected\n  actual   $actual")
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    fun render(outcome: Outcome): JsonNode = when (outcome) {
        is Outcome.Decided -> render(outcome.decision)
        is Outcome.Rejected -> json.objectNode().put("decision", "Rejected").put("reason", outcome.reason.name)
        is Outcome.Answered -> answered(outcome)

        Outcome.Cancelled -> json.objectNode().put("outcome", "none")
        is Outcome.NotCompiled -> json.objectNode().put("decision", "NotCompiled").put("error", outcome.error)
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
    }

    private fun entry(entry: ProtectedEntry): String = when (entry) {
        is ProtectedEntry.Policy -> "policy:${entry.id.value}"
        is ProtectedEntry.Binding -> "binding:${entry.id.value}"
    }

    fun render(decision: Decision): JsonNode = when (decision) {
        is Decision.Permit -> json.objectNode().put("decision", "Permit").set("by", ref(decision.by))
        is Decision.Deny -> json.objectNode().put(
            "decision",
            "Deny"
        ).set("by", decision.by?.let(::ref) ?: json.nullNode())
        is Decision.Indeterminate -> json.objectNode().put(
            "decision",
            "Indeterminate"
        ).set("cause", cause(decision.cause))
    }

    private fun ref(ref: StatementRef): JsonNode = when (ref) {
        is StatementRef.Policy -> json.objectNode().apply {
            put("policy", ref.policy.value)
            put("statement", ref.index)
            if (ref.name.isNotEmpty()) put("name", ref.name)
            set("via", via(ref.via))
        }

        is StatementRef.ScopeRejected -> json.objectNode().put("rejected", ref.reason.name)
    }

    private fun via(via: BindingRef): JsonNode = when (via) {
        is BindingRef.Bound -> json.objectNode().put(
            "binding",
            via.id.value
        ).put("document", via.document.name.lowercase())
        is BindingRef.Attached -> json.objectNode().put("attached", via.policy.value)
        BindingRef.Builtin -> json.stringNode("builtin")
    }

    private fun cause(cause: Cause): ObjectNode = json.objectNode().put("type", cause::class.simpleName).apply {
        when (cause) {
            is Cause.MatcherFailed -> set("at", ref(cause.at)).put("guard", cause.guard)
            is Cause.MissingPolicies -> set("policies", policies(cause.policyIds))
            is Cause.AmbiguousPolicy -> set("policies", policies(cause.policyIds))
            is Cause.SourceUnavailable -> put("document", cause.document.name.lowercase())
            is Cause.NotLoaded -> put("document", cause.document.name.lowercase())
            is Cause.MembershipUnavailable -> Unit
            is Cause.RequiredListenerFailed ->
                set("listeners", json.arrayNode().apply { cause.listeners.forEach(::add) })
            is Cause.AttributeUnavailable -> {
                set("attributes", json.arrayNode().apply { cause.attributes.sorted().forEach(::add) })
                cause.at?.let { set("at", ref(it)) }
            }
        }
    }

    private fun policies(ids: Set<PolicyId>): JsonNode =
        json.arrayNode().apply { ids.map { it.value }.sorted().forEach(::add) }

    private fun keys(keys: IndexKeys): JsonNode = when (keys) {
        IndexKeys.Unconstrained -> json.stringNode("any")
        is IndexKeys.Only -> json.arrayNode().apply { keys.values.sorted().forEach(::add) }
    }
}
