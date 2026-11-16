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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * What the control API answers, as JSON (RFC 0007 §10.2, §10.3): a document's history, a simulation, an explanation
 * and the decision it shows, a diff, an access report.
 */
internal object ControlResponseJson {
    private val factory = JsonNodeFactory.instance

    fun history(history: List<RevisionSummary>) = factory.arrayNode().apply {
        history.forEach {
            addObject().put(
                "revision",
                it.revision
            ).put("digest", it.digest).put("publishedAt", it.publishedAt.toString())
        }
    }

    fun simulation(simulation: Simulation): ObjectNode = factory.objectNode().apply {
        set("enforced", decision(simulation.enforced, simulation.enforcedTrace))
        simulation.candidate?.let { set("candidate", decision(it, simulation.candidateTrace)) }
        put("differs", simulation.differs)
    }

    fun explanation(explanation: Explanation): ObjectNode = factory.objectNode().apply {
        // A document the decision did not read has no version: absent, as `at` takes it, never `null` (#1391).
        putObject("version").apply {
            put("platform", explanation.version.platform)
            explanation.version.app?.let { put("app", it) }
            explanation.version.tenant?.let { put("tenant", it) }
        }
        set("decision", decision(explanation.decision, explanation.trace))
    }

    // B4: the decision view moves to its own writer
    @Suppress("NestedBlockDepth")
    fun decision(decision: DecisionView, trace: TraceView?): ObjectNode = factory.objectNode().apply {
        put("decision", decision.decision)
        decision.by?.let { set("by", statement(it)) }
        decision.cause?.let { put("cause", it) }
        if (decision.redacted) put("redacted", true)
        trace?.let { t ->
            val node = putObject("trace").put("redacted", t.redacted)
            node.putArray("candidates").apply {
                t.entries.forEach {
                    addObject().put(
                        "effect",
                        it.effect
                    ).put("outcome", it.outcome).set("statement", statement(it.statement))
                }
            }
        }
    }

    fun statement(statement: StatementView): ObjectNode = factory.objectNode()
        .put("policy", statement.policy).put("statement", statement.statement).put("name", statement.name)
        .put("via", statement.via).put("document", statement.document)

    fun diff(diff: DocumentDiff): ObjectNode = factory.objectNode().apply {
        put("from", diff.from)
        put("to", diff.to)
        set("policies", entries(diff.policies))
        set("bindings", entries(diff.bindings))
        set("roles", entries(diff.roles))
        put("sections", diff.sections)
    }

    fun entries(changes: EntryChanges): ObjectNode = factory.objectNode().apply {
        putArray("added").apply { changes.added.forEach(::add) }
        putArray("removed").apply { changes.removed.forEach(::add) }
        putArray("changed").apply { changes.changed.forEach(::add) }
    }

    fun report(report: AccessReport): ObjectNode = factory.objectNode().apply {
        set("allows", accessEntries(report.allows))
        set("denies", accessEntries(report.denies))
        put("hidden", report.hidden)
        put("opaque", report.opaque)
    }

    fun accessEntries(entries: List<AccessEntry>) = factory.arrayNode().apply {
        entries.forEach { entry ->
            val node = addObject()
            node.put("document", entry.document).put("binding", entry.binding).put("subject", entry.subject)
            node.put("app", entry.app).put("space", entry.space).put("policy", entry.policy)
            node.put(
                "statement",
                entry.statement
            ).put("conditional", entry.conditional).put("breakGlass", entry.breakGlass)
            entry.expiresAt?.let { node.put("expiresAt", it.toString()) }
        }
    }
}
