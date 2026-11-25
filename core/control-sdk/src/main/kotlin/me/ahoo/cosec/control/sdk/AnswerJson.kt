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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.sdk.WireRead.array
import me.ahoo.cosec.control.sdk.WireRead.bool
import me.ahoo.cosec.control.sdk.WireRead.counts
import me.ahoo.cosec.control.sdk.WireRead.instant
import me.ahoo.cosec.control.sdk.WireRead.long
import me.ahoo.cosec.control.sdk.WireRead.need
import me.ahoo.cosec.control.sdk.WireRead.opt
import me.ahoo.cosec.control.sdk.WireRead.optInstant
import me.ahoo.cosec.control.sdk.WireRead.optLong
import me.ahoo.cosec.control.sdk.WireRead.optText
import me.ahoo.cosec.control.sdk.WireRead.text
import me.ahoo.cosec.control.sdk.WireRead.texts
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The control API's answers, read (`CTL-76`). By the reader's rule (`CTL-53`) a member of an answer this version does
 * not know is not read; a document's entries inside one stay strict ([DocumentJson]).
 */
internal object AnswerJson {
    /**
     * The members of a change answer the typed [Change] reads, as dotted paths of `contract/control/change.json`; with
     * [CHANGE_UNREAD], every member of the contract file, which `CTL-76`'s test holds them to.
     */
    val CHANGE_READ: Set<String> = setOf(
        "approval", "approval.approver", "approval.approver.principal", "approval.approver.tenant", "approval.at",
        "approval.candidate", "approval.covers", "approval.kind",
        "author", "author.principal", "author.tenant",
        "base", "base.digest", "base.revision",
        "candidate", "createdAt",
        "escalations", "escalations.detail", "escalations.reason", "escalations.subject",
        "findings", "findings.check", "findings.message", "findings.path", "findings.severity", "findings.status",
        "id", "key", "kind", "note", "operations",
        "origin", "origin.asserted", "origin.peer", "origin.via", "origin.via.credential", "origin.via.method",
        "origin.via.request",
        "publishedRevision",
        "shadow", "shadow.acknowledged", "shadow.closed", "shadow.closed.blocked", "shadow.closed.closedAt",
        "shadow.closed.counts", "shadow.closed.insufficient", "shadow.closed.vanished", "shadow.startedAt",
        "shadow.window",
        "skipShadow", "state", "updatedAt",
    )

    /**
     * What a [Change] leaves out: the author's `deny-nomatch` confirmations, which only the request that carried them
     * needs, and which live instances an open window still waits for, which a person reads.
     */
    val CHANGE_UNREAD: Set<String> = setOf(
        "confirmations",
        "confirmations.digest",
        "confirmations.kind",
        "confirmations.policy",
        "confirmations.statement",
        "shadow.waitingFor",
        "shadow.waitingFor.instances",
        "shadow.waitingFor.more",
    )

    fun actor(node: JsonNode): Actor = Actor(node.text("tenant"), node.text("principal"))

    fun actor(actor: Actor): ObjectNode = WireJson.objectNode().put(
        "tenant",
        actor.tenant
    ).put("principal", actor.principal)

    fun head(key: SnapshotKey, answer: String): DocumentHead {
        val node = WireJson.parse(answer)
        return DocumentHead(key, node.long("revision"), node.instant("publishedAt"), DocumentJson.content(node))
    }

    fun documents(answer: String): Page<DocumentRef> {
        val node = WireJson.parse(answer)
        return Page(node.array("documents").map { DocumentRef(WireJson.key(it.text("key"))) }, next(node))
    }

    fun changes(answer: String): Page<ChangeSummary> {
        val node = WireJson.parse(answer)
        return Page(
            node.array("changes").map {
                ChangeSummary(
                    id = it.text("id"),
                    key = WireJson.key(it.text("key")),
                    state = ChangeState.of(it.text("state")),
                    kind = it.optText("kind")?.let(ChangeKind::of),
                    note = it.optText("note"),
                    author = it.opt("author")?.let(::actor),
                    createdAt = it.optInstant("createdAt"),
                    updatedAt = it.optInstant("updatedAt"),
                )
            },
            next(node),
        )
    }

    private fun next(node: JsonNode): Cursor? = node.optText("next")?.let(Cursor::of)

    fun change(answer: String): Change = change(WireJson.parse(answer))

    /** The members of a check's answer the typed [ChangeCheck] reads: every member of `contract/control/change-check.json`. */
    val CHECK_READ: Set<String> = setOf(
        "state", "base", "base.revision", "base.digest",
        "findings", "findings.check", "findings.message", "findings.path", "findings.severity", "findings.status",
        "escalations", "escalations.detail", "escalations.reason", "escalations.subject",
    )

    fun check(answer: String): ChangeCheck {
        val node = WireJson.parse(answer)
        return ChangeCheck(
            state = ChangeState.of(node.text("state")),
            base = base(node.need("base")),
            findings = findings(node),
            escalations = escalations(node),
        )
    }

    private fun base(node: JsonNode) = ChangeBase(node.long("revision"), node.optText("digest"))

    private fun findings(node: JsonNode) = node.array("findings").map {
        Finding(
            it.text("check"),
            it.text("path"),
            it.text("message"),
            it.long("status").toInt(),
            Severity.of(it.text("severity"))
        )
    }

    private fun escalations(node: JsonNode) = node.array("escalations").map {
        Escalation(it.text("subject"), EscalationReason.of(it.text("reason")), it.text("detail"))
    }

    private fun change(node: JsonNode): Change = Change(
        id = node.text("id"),
        key = WireJson.key(node.text("key")),
        kind = ChangeKind.of(node.text("kind")),
        state = ChangeState.of(node.text("state")),
        base = base(node.need("base")),
        note = node.text("note"),
        author = actor(node.need("author")),
        operations = node.array("operations").map(OperationJson::operation),
        findings = findings(node),
        escalations = escalations(node),
        candidate = node.optText("candidate"),
        publishedRevision = node.optLong("publishedRevision"),
        skipShadow = node.optText("skipShadow"),
        shadow = node.opt("shadow")?.let(::shadow),
        approval = node.opt("approval")?.let {
            Approval(
                actor(it.need("approver")),
                it.instant("at"),
                it.text("candidate"),
                it.texts("covers"),
                it.optText("kind")?.let(ApproverKind::of),
            )
        },
        origin = node.opt("origin")?.let {
            val via = it.opt("via")
            Origin(
                it.bool("asserted", false),
                it.optText("peer"),
                via?.text("method"),
                via?.optText("credential"),
                via?.optText("request"),
            )
        },
        createdAt = node.instant("createdAt"),
        updatedAt = node.instant("updatedAt"),
    )

    private fun shadow(node: JsonNode): Shadow = Shadow(
        window = node.text("window"),
        startedAt = node.instant("startedAt"),
        closed = node.opt("closed")?.let {
            ShadowClosed(
                it.instant("closedAt"),
                it.counts("counts"),
                it.bool("insufficient", false),
                it.bool("blocked", false),
                it.texts("vanished"),
            )
        },
        acknowledged = node.optLong("acknowledged"),
    )
}
