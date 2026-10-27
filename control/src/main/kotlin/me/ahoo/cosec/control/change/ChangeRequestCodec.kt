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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Change requests and staging slots as JSON, for every [me.ahoo.cosec.control.store.ControlStore]. Entries inside
 * operations are written as the document format writes them ([PolicyDocumentCodec]), so a stored change reads back
 * with the same entries, digests included. Reading is strict: anything else is a [SnapshotFormatException].
 */
object ChangeRequestCodec {
    private val factory = JsonNodeFactory.instance
    private val reader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    fun encode(change: ChangeRequest): ByteArray = CoSecJsonSerializer.writeValueAsBytes(tree(change))

    fun decode(bytes: ByteArray): ChangeRequest = guarded { change(read(bytes)) }

    fun encode(slot: StageSlot): ByteArray = CoSecJsonSerializer.writeValueAsBytes(
        factory.objectNode().apply {
            slot.holder?.let { put("holder", it.value) }
            slot.since?.let { put("since", it.toString()) }
        },
    )

    fun decodeSlot(slot: ByteArray): StageSlot = guarded {
        val node = read(slot)
        node.only("holder", "since")
        StageSlot(node.get("holder")?.let { ChangeId(it.text()) }, node.get("since")?.let { Instant.parse(it.text()) })
    }

    private fun tree(change: ChangeRequest): ObjectNode {
        val (kind, owner) = Revisions.ownerOf(change.key)
        val root = factory.objectNode()
        root.put("id", change.id.value)
        root.put("key", change.key.toString())
        root.putObject("base").put("revision", change.base.revision).apply {
            change.base.digest?.let { put("digest", it.toString()) }
        }
        val operations = root.putArray("operations")
        change.operations.forEach { operations.add(operationTree(it, kind, owner)) }
        root.putObject(
            "author"
        ).put("tenant", change.author.tenant.value).put("principal", change.author.principal.value)
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
        root.put("createdAt", change.createdAt.toString())
        root.put("updatedAt", change.updatedAt.toString())
        change.skipShadow?.let { root.put("skipShadow", it) }
        change.publishedRevision?.let { root.put("publishedRevision", it) }
        return root
    }

    private fun operationTree(operation: Operation, kind: DocumentKind, owner: String): ObjectNode {
        val item = factory.objectNode()
        when (operation) {
            is Operation.PutPolicy -> item.put(
                "op",
                "putPolicy"
            ).set("policy", PolicyDocumentCodec.policyTree(operation.policy))
            is Operation.RemovePolicy -> item.put("op", "removePolicy").put("id", operation.id.value)
            is Operation.PutBinding -> item.put("op", "putBinding")
                .set("binding", PolicyDocumentCodec.bindingTree(operation.binding, kind, owner))

            is Operation.RemoveBinding -> item.put("op", "removeBinding").put("id", operation.id.value)
            is Operation.PutCatalog, is Operation.SetLimits, is Operation.SetAttributes, is Operation.SetBoundary ->
                sectionTree(item, operation)
        }
        ifMatchOf(operation)?.let { item.put("ifMatch", it) }
        return item
    }

    private fun sectionTree(item: ObjectNode, operation: Operation) {
        when (operation) {
            is Operation.PutCatalog -> item.put("op", "putCatalog")
                .also { operation.catalog?.let { item.set("catalog", PolicyDocumentCodec.catalogTree(it)) } }

            is Operation.SetLimits -> item.put(
                "op",
                "setLimits"
            ).also { operation.limits?.let { item.set("limits", limitsTree(it)) } }
            is Operation.SetAttributes -> item.put("op", "setAttributes")
                .also { operation.attributes?.let { item.set("attributes", attributesTree(it)) } }

            is Operation.SetBoundary -> item.put("op", "setBoundary")
                .also { operation.boundary?.let { item.put("boundary", it.value) } }

            else -> error("Not a section operation: $operation")
        }
    }

    private fun ifMatchOf(operation: Operation): String? = when (operation) {
        is Operation.PutPolicy -> operation.ifMatch
        is Operation.RemovePolicy -> operation.ifMatch
        is Operation.PutBinding -> operation.ifMatch
        is Operation.RemoveBinding -> operation.ifMatch
        else -> null
    }

    private fun limitsTree(limits: ConditionLimits): ObjectNode = factory.objectNode()
        .put("maxNodes", limits.maxNodes).put("maxDepth", limits.maxDepth)
        .put("maxInValues", limits.maxInValues).put("maxCidrBlocks", limits.maxCidrBlocks)
        .put("maxLiteralBytes", limits.maxLiteralBytes).put("maxCost", limits.maxCost)

    private fun attributesTree(attributes: List<Attribute>): ArrayNode = factory.arrayNode().apply {
        attributes.forEach { attribute ->
            val type = PolicyDocumentCodec.ATTRIBUTE_TYPES.entries.first { it.value == attribute.type }.key
            addObject().put("path", attribute.path).put("type", type)
        }
    }

    private fun change(node: JsonNode): ChangeRequest {
        node.only(
            "id", "key", "base", "operations", "author", "note", "kind", "state", "findings", "candidate", "checkedWith",
            "createdAt", "updatedAt", "skipShadow", "publishedRevision",
        )
        val key = documentKey(node.need("key").text())
        val (kind, owner) = Revisions.ownerOf(key)
        val base = node.need("base").also { it.only("revision", "digest") }
        val author = node.need("author").also { it.only("tenant", "principal") }
        return ChangeRequest(
            id = ChangeId(node.need("id").text()),
            key = key,
            base = Base(base.need("revision").asLong(), base.get("digest")?.let { digest(it.text()) }),
            operations = node.need("operations").values().map { operation(it, kind, owner) },
            author = Actor(TenantId(author.need("tenant").text()), PrincipalId(author.need("principal").text())),
            note = node.need("note").text(),
            kind = ProvenanceKind.valueOf(node.need("kind").text()),
            state = ChangeState.valueOf(node.need("state").text()),
            findings = node.need("findings").values().map {
                it.only("check", "path", "message", "status", "severity")
                Finding(
                    it.need("check").text(),
                    it.need("path").text(),
                    it.need("message").text(),
                    it.need("status").asInt(),
                    Severity.valueOf(it.need("severity").text()),
                )
            },
            candidate = node.get("candidate")?.text(),
            checkedWith = node.need("checkedWith").properties().associate { (name, value) ->
                documentKey(name) to (if (value.isNull) null else digest(value.text()))
            },
            createdAt = Instant.parse(node.need("createdAt").text()),
            updatedAt = Instant.parse(node.need("updatedAt").text()),
            skipShadow = node.get("skipShadow")?.text(),
            publishedRevision = node.get("publishedRevision")?.asLong(),
        )
    }

    private fun operation(node: JsonNode, kind: me.ahoo.cosec.model.decision.DocumentKind, owner: String): Operation {
        val ifMatch = node.get("ifMatch")?.text()
        return when (val op = node.need("op").text()) {
            "putPolicy" -> Operation.PutPolicy(PolicyDocumentCodec.decodePolicy(node.need("policy")), ifMatch)
            "removePolicy" -> Operation.RemovePolicy(PolicyId(node.need("id").text()), ifMatch)
            "putBinding" -> Operation.PutBinding(
                PolicyDocumentCodec.decodeBinding(node.need("binding"), kind, owner),
                ifMatch
            )
            "removeBinding" -> Operation.RemoveBinding(BindingId(node.need("id").text()), ifMatch)
            "putCatalog" -> Operation.PutCatalog(node.get("catalog")?.let { PolicyDocumentCodec.decodeCatalog(it) })
            "setLimits" -> Operation.SetLimits(
                node.get("limits")?.let {
                    ConditionLimits(
                        it.need("maxNodes").asInt(),
                        it.need("maxDepth").asInt(),
                        it.need("maxInValues").asInt(),
                        it.need("maxCidrBlocks").asInt(),
                        it.need("maxLiteralBytes").asInt(),
                        it.need("maxCost").asInt(),
                    )
                },
            )

            "setBoundary" -> Operation.SetBoundary(node.get("boundary")?.let { PolicyId(it.text()) })
            "setAttributes" -> Operation.SetAttributes(
                node.get("attributes")?.values()?.map {
                    val type = PolicyDocumentCodec.ATTRIBUTE_TYPES[it.need("type").text()]
                        ?: malformed("unknown attribute type ${it.get("type")}")
                    Attribute.external(it.need("path").text(), type)
                },
            )

            else -> malformed("unknown operation [$op]")
        }
    }

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> malformed("[$text] is not a document key")
    }

    private fun digest(text: String): SnapshotDigest {
        if (!DIGEST.matches(text)) malformed("[$text] is not a digest")
        return SnapshotDigest(ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() })
    }

    private fun read(bytes: ByteArray): JsonNode = try {
        reader.readTree(bytes) ?: malformed("empty")
    } catch (error: JacksonException) {
        throw SnapshotFormatException("A change request is not JSON: ${error.originalMessage}", error)
    }

    /** Model and codec errors are format errors: a stored value this instance cannot read is never read as absent. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: SnapshotFormatException) {
        throw error
    } catch (error: JacksonException) {
        throw SnapshotFormatException("A change request is malformed: ${error.originalMessage}", error)
    } catch (error: IllegalArgumentException) {
        throw SnapshotFormatException("A change request is malformed: ${error.message}", error)
    }

    private fun JsonNode.need(name: String): JsonNode = get(name) ?: malformed("[$name] is missing")

    private fun JsonNode.text(): String = if (isString) asString() else malformed("$this is not a string")

    private fun JsonNode.only(vararg names: String) {
        if (!isObject) malformed("$this is not an object")
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        if (unknown.isNotEmpty()) malformed("unknown fields $unknown")
    }

    private fun malformed(reason: String): Nothing = throw SnapshotFormatException(
        "A change request is malformed: $reason."
    )

    private val DIGEST = Regex("^[0-9a-f]{40}$")
    private const val HEX = 16
}
