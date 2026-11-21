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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeJson.actor
import me.ahoo.cosec.control.change.ChangeJson.actorTree
import me.ahoo.cosec.control.change.ChangeJson.factory
import me.ahoo.cosec.control.change.ChangeJson.format
import me.ahoo.cosec.control.change.ChangeJson.malformed
import me.ahoo.cosec.control.change.ChangeJson.need
import me.ahoo.cosec.control.change.ChangeJson.only
import me.ahoo.cosec.control.change.ChangeJson.quoted
import me.ahoo.cosec.control.change.ChangeJson.text
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Change requests and staging slots as JSON, for every [me.ahoo.cosec.control.store.ControlStore]. Entries inside
 * operations are written as the document format writes them ([PolicyDocumentCodec]), so a stored change reads back
 * with the same entries, digests included. Reading is strict: anything else is a [SnapshotFormatException], whose
 * message escapes control characters, a document key of an app no key can name included (SCP-10).
 */
@ExperimentalCoSecApi
object ChangeRequestCodec {
    private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    fun encode(change: ChangeRequest): ByteArray = CoSecJson.mapper.writeValueAsBytes(tree(change))

    fun decode(bytes: ByteArray): ChangeRequest = guarded { change(read(bytes)) }

    fun encode(slot: StageSlot): ByteArray = CoSecJson.mapper.writeValueAsBytes(
        StoredFormat.write(factory.objectNode()).apply {
            slot.holder?.let { put("holder", it.value) }
            slot.since?.let { put("since", it.toString()) }
        },
    )

    fun decodeSlot(slot: ByteArray): StageSlot = guarded {
        val node = read(slot)
        StoredFormat.of(node, "staging slot")
        StageSlot(node.get("holder")?.let { ChangeId(it.text()) }, node.get("since")?.let { Instant.parse(it.text()) })
    }

    private fun tree(change: ChangeRequest): ObjectNode {
        val (kind, owner) = ownerOf(change.key)
        val root = StoredFormat.write(factory.objectNode())
        root.put("id", change.id.value)
        root.put("key", change.key.toString())
        root.putObject("base").put("revision", change.base.revision).apply {
            change.base.digest?.let { put("digest", it.toString()) }
        }
        val operations = root.putArray("operations")
        change.operations.forEach { operations.add(OperationCodec.tree(it, kind, owner)) }
        root.set("author", actorTree(change.author))
        root.put("note", change.note)
        root.put("kind", change.kind.name)
        root.put("state", change.state.name)
        val findings = root.putArray("findings")
        change.findings.forEach {
            findings.addObject().put("check", it.check).put("path", it.path).put("message", it.message)
                .put("status", it.status).put("severity", it.severity.name)
        }
        change.candidate?.let { root.put("candidate", it) }
        val checkedWith = root.putObject("checkedWith")
        change.checkedWith.forEach { (key, digest) -> checkedWith.put(key.toString(), digest?.toString()) }
        change.checkedApps?.let { root.putObject("checkedApps").put("digest", it.digest?.toString()) }
        root.put("createdAt", change.createdAt.toString())
        root.put("updatedAt", change.updatedAt.toString())
        change.skipShadow?.let { root.put("skipShadow", it) }
        change.publishedRevision?.let { root.put("publishedRevision", it) }
        approvalTree(root, change)
        change.shadow?.let { root.set("shadow", ShadowStateCodec.tree(it)) }
        if (change.confirmations.isNotEmpty()) {
            val confirmations = root.putArray("confirmations")
            change.confirmations.forEach {
                confirmations.addObject().put("policy", it.policy.value).put("statement", it.statement)
                    .put("digest", it.digest).put("kind", it.kind)
            }
        }
        return root
    }

    /** The escalations and the approval of [change] (RFC 0007 §7.5); neither is written while there is none. */

    private fun approvalTree(root: ObjectNode, change: ChangeRequest) {
        if (change.escalations.isNotEmpty()) {
            val escalations = root.putArray("escalations")
            change.escalations.forEach {
                escalations.addObject().put(
                    "subject",
                    it.subject
                ).put("reason", it.reason.name).put("detail", it.detail)
            }
        }
        change.approval?.let { ApprovalCodec.tree(root.putObject("approval"), it) }
    }

    private fun confirmation(node: JsonNode): Confirmation {
        return Confirmation(
            PolicyId(node.need("policy").text()),
            node.need("statement").asInt(),
            node.need("digest").text(),
            node.need("kind").text()
        )
    }

    private fun change(node: JsonNode): ChangeRequest {
        // The reader's rule (CTL-53): members of a known format this version does not know are ignored. The operations
        // stay as strict as the document format they carry: an operation is understood, or the change does not read.
        StoredFormat.of(node, "change request")
        val key = documentKey(node.need("key").text())
        val (kind, owner) = ownerOf(key)
        val base = node.need("base")
        return ChangeRequest(
            id = ChangeId(node.need("id").text()),
            key = key,
            base = Base(base.need("revision").asLong(), base.get("digest")?.let { digest(it.text()) }),
            operations = node.need("operations").values().map { OperationCodec.decode(it, kind, owner) },
            author = actor(node.need("author")),
            note = node.need("note").text(),
            kind = ProvenanceKind.valueOf(node.need("kind").text()),
            state = ChangeState.valueOf(node.need("state").text()),
            findings = node.need("findings").values().map {
                Finding(
                    it.need("check").text(),
                    it.need("path").text(),
                    it.need("message").text(),
                    it.need("status").asInt(),
                    Severity.valueOf(it.need("severity").text()),
                )
            },
            candidate = node.get("candidate")?.text(),
            checkedWith = node.need("checkedWith").properties()
                .associate { (name, value) -> documentKey(name) to (if (value.isNull) null else digest(value.text())) },
            createdAt = Instant.parse(node.need("createdAt").text()),
            updatedAt = Instant.parse(node.need("updatedAt").text()),
            skipShadow = node.get("skipShadow")?.text(),
            publishedRevision = node.get("publishedRevision")?.asLong(),
            escalations = node.get("escalations")?.values()?.map {
                Escalation(
                    it.need("subject").text(),
                    Escalation.Reason.of(it.need("reason").text()),
                    it.need("detail").text()
                )
            }.orEmpty(),
            shadow = node.get("shadow")?.let(ShadowStateCodec::decode),
            confirmations = node.get("confirmations")?.values()?.map(::confirmation).orEmpty(),
            approval = node.get("approval")?.let(ApprovalCodec::decode),
            checkedApps = node.get("checkedApps")?.let { apps ->
                CheckedApps(apps.get("digest")?.takeUnless { it.isNull }?.let { digest(it.text()) })
            },
        )
    }

    /** An operation as the control API's request bodies write it: the stored form (RFC 0007 §10.2). */
    @InternalCoSecApi
    fun operation(key: SnapshotKey, node: JsonNode): Operation = guarded {
        val (kind, owner) = ownerOf(key)
        node.only(
            "op", "policy", "id", "binding", "rateLimit", "role", "catalog", "limits", "attributes", "boundary", "ifMatch",
        )
        OperationCodec.decode(node, kind, owner)
    }

    /** `platform`, `app:<id>` or `tenant:<id>`, as the control API's paths write a document. */
    fun documentKeyOf(text: String): SnapshotKey = guarded { documentKey(text) }

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw format("[${quoted(text)}] is not a document key")
    }

    private fun digest(text: String): SnapshotDigest {
        if (!SnapshotDigest.isDigest(text)) throw format("[${quoted(text)}] is not a digest")
        return SnapshotDigest.parse(text)
    }

    // stays: Jackson's exception quotes the stored bytes; only where it failed is kept.
    @Suppress("SwallowedException")
    private fun read(bytes: ByteArray): JsonNode = try {
        reader.readTree(bytes) ?: malformed("empty")
    } catch (error: JacksonException) {
        // Where, never what: Jackson's message and the exception quote the stored bytes, which a log line would carry.
        throw SnapshotFormatException("A change request is not JSON${error.at}.")
    }

    /** Model and codec errors are format errors: a stored value this instance cannot read is never read as absent. */
    // stays: a cause's message quotes the stored bytes, and whoever reads the value logs the exception with its causes.
    @Suppress("SwallowedException")
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: SnapshotFormatException) {
        // The document format's codecs, which read a change's entries, quote what they read as they found it.
        throw SnapshotFormatException(SnapshotKeyText.message(error.message.orEmpty()))
    } catch (error: JacksonException) {
        throw SnapshotFormatException("A change request is malformed${error.at}.")
    } catch (error: IllegalArgumentException) {
        throw SnapshotFormatException("A change request is malformed: ${quoted(error.message)}.")
    } catch (error: java.time.format.DateTimeParseException) {
        // A stored time that does not parse is a format error like any other, never an unexpected failure.
        throw SnapshotFormatException("A change request is malformed: ${quoted(error.message)}.")
    }

    /** Where Jackson failed, never what it read. */
    private val JacksonException.at: String
        get() = location?.let { " at line ${it.lineNr}, column ${it.columnNr}" }.orEmpty()
}
