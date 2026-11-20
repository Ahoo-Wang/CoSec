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
import me.ahoo.cosec.control.change.ChangeJson.format
import me.ahoo.cosec.control.change.ChangeJson.need
import me.ahoo.cosec.control.change.ChangeJson.only
import me.ahoo.cosec.control.change.ChangeJson.quoted
import me.ahoo.cosec.control.change.ChangeJson.text
import me.ahoo.cosec.document.DocumentBudgets
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

            is Operation.SetLimits -> item.put("op", "setLimits")
                .also { limitsTree(operation.limits, operation.budgets)?.let { item.set("limits", it) } }
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

    /** The `limits` of a `setLimits`: null when it sets neither, back to the defaults. */
    private fun limitsTree(limits: ConditionLimits?, budgets: DocumentBudgets?): ObjectNode? {
        if (limits == null && budgets == null) {
            return null
        }
        val node = factory.objectNode()
        limits?.let {
            node.put("maxNodes", it.maxNodes).put("maxDepth", it.maxDepth)
                .put("maxInValues", it.maxInValues).put("maxCidrBlocks", it.maxCidrBlocks)
                .put("maxLiteralBytes", it.maxLiteralBytes).put("maxCost", it.maxCost)
        }
        budgets?.let {
            node.put("maxPolicies", it.maxPolicies).put("maxStatements", it.maxStatements)
                .put("maxRoleBindings", it.maxRoleBindings).put("maxAnyoneBindings", it.maxAnyoneBindings)
                .put("maxChangeOperations", it.maxChangeOperations)
        }
        return node
    }

    private fun attributesTree(attributes: List<Attribute>): ArrayNode = factory.arrayNode().apply {
        attributes.forEach { attribute ->
            val type = PolicyDocumentCodec.ATTRIBUTE_TYPES.entries.first { it.value == attribute.type }.key
            addObject().put("path", attribute.path).put("type", type)
        }
    }

    internal fun decode(node: JsonNode, kind: me.ahoo.cosec.model.decision.DocumentKind, owner: String): Operation {
        val op = node.need("op").text()
        // The members of this operation and no other's (R2C-33): `ifMatch` guards an entry, so a section has none.
        FIELDS[op]?.let { fields ->
            if (node.has("ifMatch") && "ifMatch" !in fields) {
                throw format(
                    "operation [${quoted(op)}] takes no ifMatch: it replaces a section, which the change's base " +
                        "(the digest of the document it was proposed on) guards"
                )
            }
            node.only(*fields)
        }
        val ifMatch = node.get("ifMatch")?.text()
        return when (op) {
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

    /** The members of each operation as the control API writes it: an entry's operations carry an `ifMatch`, a section's none. */
    private val FIELDS: Map<String, Array<String>> = mapOf(
        "putPolicy" to arrayOf("op", "policy", "ifMatch"),
        "removePolicy" to arrayOf("op", "id", "ifMatch"),
        "putBinding" to arrayOf("op", "binding", "ifMatch"),
        "removeBinding" to arrayOf("op", "id", "ifMatch"),
        "putRateLimit" to arrayOf("op", "rateLimit", "ifMatch"),
        "removeRateLimit" to arrayOf("op", "id", "ifMatch"),
        "putRole" to arrayOf("op", "role", "ifMatch"),
        "removeRole" to arrayOf("op", "id", "ifMatch"),
        "putCatalog" to arrayOf("op", "catalog"),
        "setLimits" to arrayOf("op", "limits"),
        "setAttributes" to arrayOf("op", "attributes"),
        "setBoundary" to arrayOf("op", "boundary"),
    )

    /** The operations on a document's sections, which have no `ifMatch`. */
    private fun section(node: JsonNode, op: String): Operation {
        return when (op) {
            "putCatalog" -> Operation.PutCatalog(node.get("catalog")?.let { PolicyDocumentCodec.decodeCatalog(it) })
            "setLimits" -> node.get("limits").let { limits ->
                Operation.SetLimits(
                    limits?.let(::limits),
                    limits?.let { PolicyDocumentCodec.decodeBudgets(it, "\$.limits") }
                )
            }
            "setBoundary" -> Operation.SetBoundary(node.get("boundary")?.let { PolicyId(it.text()) })
            // The document format's rules, so a section never reads a value its document would refuse (#1275).
            "setAttributes" -> Operation.SetAttributes(
                node.get("attributes")?.let { PolicyDocumentCodec.decodeAttributes(it, "\$.attributes") }
            )

            else -> throw format("unknown operation [${quoted(op)}]")
        }
    }

    /**
     * The condition limits as the document format reads them, with none left to its default: an operation states all
     * six. The budgets beside them may be left out, each then its default (`CTL-40`).
     */
    private fun limits(node: JsonNode): ConditionLimits {
        LIMIT_FIELDS.forEach { field -> node.need(field) }
        return checkNotNull(PolicyDocumentCodec.decodeLimits(node, "\$.limits"))
    }
}
