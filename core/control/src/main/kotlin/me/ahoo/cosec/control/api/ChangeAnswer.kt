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
import me.ahoo.cosec.control.change.Acknowledgement
import me.ahoo.cosec.control.change.ChangeJson.actorTree
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.OperationCodec
import me.ahoo.cosec.control.change.ShadowStateCodec
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * A change as the control API answers it (R2C-11, `CTL-61`): the part of the record a client is given, written member
 * by member, never a copy of the stored tree. What the store keeps for its own work stays out of every answer: the heads
 * the candidate was checked with (`checkedWith`, `checkedApps`), the instances that reported in its shadow window, the
 * record's `format`, and what an approver held or which credential they approved with (reviews D5, N2). A member
 * added to the record reaches no client until it is written here. `contract/control/change.json` pins the members.
 *
 * The values of `kind`, `state`, `severity` and an escalation's `reason` are the names of the control plane's own enums,
 * pinned by `contract/control/vocabularies.json`; an operation is written as the document format writes its entry.
 */
@InternalCoSecApi
object ChangeAnswer {
    private val factory = JsonNodeFactory.instance

    /**
     * [change], with which live instances acknowledge its open shadow window when [acknowledgement] is read, and its
     * author's origin, whole when [wholeOrigin] ([origin]).
     */
    fun encode(
        change: ChangeRequest,
        acknowledgement: Acknowledgement? = null,
        wholeOrigin: Boolean = false
    ): ByteArray =
        CoSecJson.mapper.writeValueAsBytes(tree(change, acknowledgement, wholeOrigin))

    fun tree(change: ChangeRequest, acknowledgement: Acknowledgement? = null, wholeOrigin: Boolean = false): ObjectNode {
        val (kind, owner) = ownerOf(change.key)
        val root = factory.objectNode().put("id", change.id.value).put("key", change.key.toString())
        root.putObject("base").put("revision", change.base.revision).apply {
            change.base.digest?.let { put("digest", it.toString()) }
        }
        root.putArray("operations").apply { change.operations.forEach { add(OperationCodec.tree(it, kind, owner)) } }
        root.set("author", actorTree(change.author))
        origin(root, change, wholeOrigin)
        root.put("note", change.note).put("kind", change.kind.name).put("state", change.state.name)
        root.putArray("findings").apply {
            change.findings.forEach {
                addObject().put("check", it.check).put("path", it.path).put("message", it.message)
                    .put("status", it.status).put("severity", it.severity.name)
            }
        }
        change.candidate?.let { root.put("candidate", it) }
        root.put("createdAt", change.createdAt.toString()).put("updatedAt", change.updatedAt.toString())
        change.skipShadow?.let { root.put("skipShadow", it) }
        change.publishedRevision?.let { root.put("publishedRevision", it) }
        approval(root, change)
        shadowAndConfirmations(root, change, acknowledgement)
        return root
    }

    /**
     * Its shadow window as a client reads it, without the instances that reported, and what its author confirmed. While
     * the window is open, `GET /changes/{id}` adds how many live instances acknowledge it and which do not
     * (`waitingFor`: at most [Acknowledgement.SHOWN] ids, sorted, and how many `more`), `CTL-10`.
     */
    private fun shadowAndConfirmations(root: ObjectNode, change: ChangeRequest, acknowledgement: Acknowledgement?) {
        change.shadow?.let { shadow ->
            val node = root.putObject("shadow")
                .put("window", shadow.window)
                .put("startedAt", shadow.startedAt.toString())
            shadow.closed?.let { ShadowStateCodec.summary(node.putObject("closed"), it) }
            acknowledgement?.takeIf { shadow.closed == null }?.let { acknowledged(node, it) }
        }
        if (change.confirmations.isNotEmpty()) {
            root.putArray("confirmations").apply {
                change.confirmations.forEach {
                    addObject().put("policy", it.policy.value).put("statement", it.statement)
                        .put("digest", it.digest).put("kind", it.kind)
                }
            }
        }
    }

    private fun acknowledged(node: ObjectNode, acknowledgement: Acknowledgement) {
        node.put("acknowledged", acknowledgement.acknowledged)
        val waiting = acknowledgement.waitingFor
        node.putObject("waitingFor").put("more", maxOf(0, waiting.size - Acknowledgement.SHOWN))
            .putArray("instances").apply { waiting.take(Acknowledgement.SHOWN).forEach(::add) }
    }

    /**
     * How the author was identified (`CTL-70`, the record's `CTL-57`), absent for a change no request made. Every reader
     * of the change is told whether the identity was only stated (`asserted`) and by what kind of credential
     * (`via.method`): what a reviewer weighs a change by. The credential's id, the request's id and the connection's
     * address (`peer`) say which key or token, from where: only a reader [wholeOrigin] admits ([ControlPlane.origins])
     * is given them, and every other reader is told nothing of them, not even that they exist.
     */
    private fun origin(root: ObjectNode, change: ChangeRequest, wholeOrigin: Boolean) {
        val origin = change.origin ?: return
        val node = root.putObject("origin").put("asserted", origin.asserted)
        origin.via?.let { via ->
            val out = node.putObject("via").put("method", via.method)
            if (wholeOrigin) {
                via.credentialId?.let { out.put("credential", it) }
                via.requestId?.let { out.put("request", it) }
            }
        }
        if (wholeOrigin) origin.peer?.let { node.put("peer", it) }
    }

    /** The escalations, and the approval as far as it is shown: who approved, when, for which candidate and what. */
    private fun approval(root: ObjectNode, change: ChangeRequest) {
        if (change.escalations.isNotEmpty()) {
            root.putArray("escalations").apply {
                change.escalations.forEach {
                    addObject().put("subject", it.subject).put("reason", it.reason.name).put("detail", it.detail)
                }
            }
        }
        change.approval?.let { approval ->
            val node = root.putObject("approval")
            node.set("approver", actorTree(approval.approver))
            node.put("at", approval.at.toString()).put("candidate", approval.candidate)
            node.putArray("covers").apply { approval.covers.forEach { add(it) } }
        }
    }
}
