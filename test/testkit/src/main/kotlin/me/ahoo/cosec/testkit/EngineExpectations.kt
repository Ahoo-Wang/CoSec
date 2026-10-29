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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * Compares an [EngineOutcome] with a scenario's `expect`. Outcomes are rendered in the scenario syntax and compared as
 * JSON:
 *
 * ```jsonc
 * { "decision": "Permit", "by": { "policy": "p", "statement": 0, "via": { "binding": "b", "document": "platform" } } }
 * { "decision": "Deny", "by": null }                                   // the implicit deny
 * { "decision": "Deny", "by": { "boundary": "b", "tenant": "acme" } }   // outside the platform boundary
 * { "decision": "Indeterminate", "cause": { "type": "MissingPolicies", "policies": ["p"] } }
 * { "decision": "Rejected", "reason": "SPACE_NOT_MEMBER" }
 * { "decision": "NotCompiled", "error": "a part of the error message" }
 * ```
 *
 * `via` is `{ "binding": id, "document": "platform" | "app" | "tenant" }`, `{ "attached": policy }` or `"builtin"`; a
 * statement's `name` appears when it has one. Errors are not compared, only where they happened.
 *
 * An exact comparison (the conformance suite) requires the `expect` to say everything about the decision. Otherwise
 * only the fields the `expect` names are compared, so `{ "decision": "Permit" }` passes whichever statement permits.
 */
@ExperimentalCoSecApi
object EngineExpectations {
    private val json = JsonNodeFactory.instance

    /** Throws [AssertionError] naming the scenario [name] when [outcome] is not what [expected] says. */
    fun check(name: String, expected: JsonNode, outcome: EngineOutcome, exact: Boolean) {
        val notCompiled = expected.get("decision")?.textOrNull() == "NotCompiled"
        if (outcome is EngineOutcome.NotCompiled || notCompiled) {
            if (outcome !is EngineOutcome.NotCompiled) {
                fail("$name: expected $expected, but the documents compile: ${render(outcome)}")
            }
            if (!notCompiled) {
                fail("$name: expected $expected, but the documents do not compile: ${outcome.error}")
            }
            expected.only("decision", "error")
            if (expected.required("error").text() !in outcome.error) {
                fail("$name: expected the error to mention $expected, but it is: ${outcome.error}")
            }
            return
        }
        val rendered = render(outcome)
        val actual = if (exact) rendered else rendered.fieldsOf(expected)
        if (actual != expected) {
            fail("$name:\n  expected $expected\n  actual   $rendered")
        }
    }

    /** The fields of this that [expected] names; a field it names that this lacks reads as missing, so it differs. */
    private fun JsonNode.fieldsOf(expected: JsonNode): JsonNode = json.objectNode().also { kept ->
        expected.fields().keys.forEach { field -> get(field)?.let { kept.set(field, it) } }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    fun render(outcome: EngineOutcome): JsonNode = when (outcome) {
        is EngineOutcome.Decided -> render(outcome.decision)
        is EngineOutcome.Rejected -> json.objectNode().put("decision", "Rejected").put("reason", outcome.reason.name)
        is EngineOutcome.NotCompiled -> json.objectNode().put("decision", "NotCompiled").put("error", outcome.error)
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

    /** A statement reference, as `by` and `at` render it. */
    fun ref(ref: StatementRef): JsonNode = when (ref) {
        is StatementRef.Policy -> json.objectNode().apply {
            put("policy", ref.policy.value)
            put("statement", ref.index)
            if (ref.name.isNotEmpty()) put("name", ref.name)
            set("via", via(ref.via))
        }

        is StatementRef.ScopeRejected -> json.objectNode().put("rejected", ref.reason.name)
        is StatementRef.Boundary -> json.objectNode().put(
            "boundary",
            ref.boundary.value
        ).put("tenant", ref.tenant.value)
    }

    private fun via(via: BindingRef): JsonNode = when (via) {
        is BindingRef.Bound -> json.objectNode().put(
            "binding",
            via.id.value
        ).put("document", via.document.name.lowercase())
        is BindingRef.Attached -> json.objectNode().put("attached", via.policy.value)
        BindingRef.Builtin -> json.stringNode("builtin")
        is BindingRef.Boundary -> json.objectNode().put("boundaryOf", via.tenant.value)
    }

    private fun cause(cause: Cause): ObjectNode = json.objectNode().put("type", cause::class.simpleName).apply {
        when (cause) {
            is Cause.MatcherFailed -> set("at", ref(cause.at)).put("guard", cause.guard)
            is Cause.MissingPolicies -> set("policies", policies(cause.policyIds))
            is Cause.AmbiguousPolicy -> set("policies", policies(cause.policyIds))
            is Cause.SourceUnavailable, is Cause.NotLoaded -> put("document", documentOf(cause))
            is Cause.MembershipUnavailable -> Unit
            is Cause.BoundaryUnavailable -> put("boundary", cause.boundary.value)
            is Cause.RateLimiterUnavailable -> put("rule", cause.ruleId)
            is Cause.RequiredListenerFailed ->
                set("listeners", json.arrayNode().apply { cause.listeners.forEach(::add) })
            is Cause.AttributeUnavailable -> {
                set("attributes", json.arrayNode().apply { cause.attributes.sorted().forEach(::add) })
                cause.at?.let { set("at", ref(it)) }
            }
        }
    }

    private fun documentOf(cause: Cause): String = when (cause) {
        is Cause.SourceUnavailable -> cause.document.name.lowercase()
        is Cause.NotLoaded -> cause.document.name.lowercase()
        else -> error("No document: $cause")
    }

    private fun policies(ids: Set<PolicyId>): JsonNode =
        json.arrayNode().apply { ids.map { it.value }.sorted().forEach(::add) }
}
