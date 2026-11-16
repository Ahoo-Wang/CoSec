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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeJson.factory
import me.ahoo.cosec.control.change.ChangeJson.malformed
import me.ahoo.cosec.control.change.ChangeJson.need
import me.ahoo.cosec.control.change.ChangeJson.text
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** A change's operations and the sections they put, both ways. */
internal object OperationCodec {
    private val LIMIT_FIELDS = listOf(
        "maxNodes",
        "maxDepth",
        "maxInValues",
        "maxCidrBlocks",
        "maxLiteralBytes",
        "maxCost"
    )

    internal fun tree(operation: Operation, kind: DocumentKind, owner: String): ObjectNode {
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
            is Operation.PutRateLimit -> item.put("op", "putRateLimit")
                .set("rateLimit", PolicyDocumentCodec.rateLimitTree(operation.rule))

            is Operation.RemoveRateLimit -> item.put("op", "removeRateLimit").put("id", operation.id)
            is Operation.PutRole -> item.put("op", "putRole").set("role", PolicyDocumentCodec.roleTree(operation.role))
            is Operation.RemoveRole -> item.put("op", "removeRole").put("id", operation.id.value)
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
        is Operation.PutRateLimit -> operation.ifMatch
        is Operation.RemoveRateLimit -> operation.ifMatch
        is Operation.PutRole -> operation.ifMatch
        is Operation.RemoveRole -> operation.ifMatch
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

    internal fun decode(node: JsonNode, kind: me.ahoo.cosec.model.decision.DocumentKind, owner: String): Operation {
        val ifMatch = node.get("ifMatch")?.text()
        return when (val op = node.need("op").text()) {
            "putPolicy" -> Operation.PutPolicy(PolicyDocumentCodec.decodePolicy(node.need("policy")), ifMatch)
            "removePolicy" -> Operation.RemovePolicy(PolicyId(node.need("id").text()), ifMatch)
            "putBinding" -> Operation.PutBinding(
                PolicyDocumentCodec.decodeBinding(node.need("binding"), kind, owner),
                ifMatch
            )
            "removeBinding" -> Operation.RemoveBinding(BindingId(node.need("id").text()), ifMatch)
            "putRateLimit" -> Operation.PutRateLimit(
                PolicyDocumentCodec.decodeRateLimit(node.need("rateLimit")),
                ifMatch
            )
            "removeRateLimit" -> Operation.RemoveRateLimit(node.need("id").text(), ifMatch)
            "putRole" -> Operation.PutRole(PolicyDocumentCodec.decodeRole(node.need("role"), kind), ifMatch)
            "removeRole" -> Operation.RemoveRole(RoleId(node.need("id").text()), ifMatch)
            else -> section(node, op)
        }
    }

    /** The operations on a document's sections, which have no `ifMatch`. */
    private fun section(node: JsonNode, op: String): Operation {
        return when (op) {
            "putCatalog" -> Operation.PutCatalog(node.get("catalog")?.let { PolicyDocumentCodec.decodeCatalog(it) })
            "setLimits" -> Operation.SetLimits(node.get("limits")?.let(::limits))
            "setBoundary" -> Operation.SetBoundary(node.get("boundary")?.let { PolicyId(it.text()) })
            // The document format's rules, so a section never reads a value its document would refuse (#1275).
            "setAttributes" -> Operation.SetAttributes(
                node.get("attributes")?.let { PolicyDocumentCodec.decodeAttributes(it, "\$.attributes") }
            )

            else -> malformed("unknown operation [$op]")
        }
    }

    /** Limits as the document format reads them, with none left to its default: an operation states all six. */
    private fun limits(node: JsonNode): ConditionLimits = PolicyDocumentCodec.decodeLimits(node, "\$.limits").also {
        LIMIT_FIELDS.forEach { field -> node.need(field) }
    }
}
