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
import me.ahoo.cosec.control.change.InstanceReaders
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * What the control API answers, as JSON (RFC 0007 §10.2, §10.3): a document's history, a simulation, an explanation
 * and the decision it shows, a diff, an access report.
 */
internal object ControlResponseJson {
    private val factory = JsonNodeFactory.instance

    /**
     * A page of `GET /changes`: each change as a summary of its own (R2C-11), whose members are a change answer's members
     * of the same name ([ChangeAnswer]), `UNREADABLE` for one whose record does not read (CTL-53), and `next`, the cursor
     * of the next page or null. Nothing counts what the caller may not see (CTL-55).
     */
    fun changes(page: ChangePage): ObjectNode {
        val root = factory.objectNode()
        val changes = root.putArray("changes")
        page.changes.forEach { changes.add(summary(it)) }
        return root.put("next", page.next)
    }

    private fun summary(listed: ListedChange): ObjectNode {
        val node = factory.objectNode().put("id", listed.id.value).put("key", listed.document.toString())
        val change = (listed as? ListedChange.Readable)?.change ?: return node.put("state", "UNREADABLE")
        node.put("state", change.state.name).put("kind", change.kind.name)
        node.putObject(
            "author"
        ).put("tenant", change.author.tenant.value).put("principal", change.author.principal.value)
        return node.put("note", change.note).put("createdAt", change.createdAt.toString())
            .put("updatedAt", change.updatedAt.toString())
    }

    /** A revision's propagation (`CTL-62`): how many recent instances hold the document, and have installed it. */
    fun propagation(propagation: Propagation): ObjectNode = factory.objectNode()
        .put("revision", propagation.revision)
        .put("installed", propagation.installed)
        .put("holding", propagation.holding)
        .put("recent", propagation.recent)
        .put("unknown", propagation.unknown)
        .put("asOf", propagation.asOf.toString())

    /**
     * The readers of the store (`CTL-89`): `instances`, each one's `instance`, `release` (absent when its status does not
     * name one), `formatLevel`, `at`, whether it is `live` (a status in the minute before `asOf`) and whether it `shadows`;
     * `unreadable`, the instances whose status does not decode; `releases`, how many instances run each release
     * (`unknown` for both kinds without one); and `asOf`, when the statuses were read.
     */
    fun instances(readers: InstanceReaders): ObjectNode {
        val root = factory.objectNode()
        val liveSince = readers.asOf.minus(ShadowReportReader.STATUS_TTL)
        val instances = root.putArray("instances")
        readers.statuses.sortedBy { it.instance }.forEach { status ->
            val node = instances.addObject().put("instance", status.instance)
            status.release?.let { node.put("release", it) }
            node.put("formatLevel", status.formatLevel).put("at", status.at.toString())
                .put("live", !status.at.isBefore(liveSince)).put("shadows", status.shadows)
        }
        root.putArray("unreadable").apply { readers.unreadable.sorted().forEach(::add) }
        root.putObject("releases").apply { readers.releases.forEach { (release, count) -> put(release, count) } }
        return root.put("asOf", readers.asOf.toString())
    }

    /**
     * A page of `GET /documents` (`CTL-68`): each document by its key, `next`, the cursor of the next page or null, and
     * `asOf`, when the document list was read.
     */
    fun documents(page: DocumentPage): ObjectNode {
        val root = factory.objectNode()
        val documents = root.putArray("documents")
        page.documents.forEach { documents.addObject().put("key", it.toString()) }
        return root.put("next", page.next).put("asOf", page.asOf.toString())
    }

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

    fun decision(decision: DecisionView, trace: TraceView?): ObjectNode = factory.objectNode().apply {
        put("decision", decision.decision)
        decision.by?.let { set("by", statement(it)) }
        decision.cause?.let { put("cause", it) }
        decision.reason?.let { put("reason", it) }
        if (decision.redacted) put("redacted", true)
        trace?.let { trace(putObject("trace"), it) }
    }

    /** [trace] written into [node]: whether it is redacted, and each candidate statement with its effect and outcome. */
    private fun trace(node: ObjectNode, trace: TraceView) {
        node.put("redacted", trace.redacted)
        node.putArray("candidates").apply {
            trace.entries.forEach {
                addObject().put(
                    "effect",
                    it.effect
                ).put("outcome", it.outcome).set("statement", statement(it.statement))
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
        putArray("sections").apply { diff.sections.forEach(::add) }
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
