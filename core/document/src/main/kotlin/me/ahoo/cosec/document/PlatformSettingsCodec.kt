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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The platform document's settings: its `limits`, which hold the condition limits and the entry budgets
 * ([DocumentBudgets]), and the external attributes it declares.
 */
internal object PlatformSettingsCodec {
    private val LIMIT_FIELDS = arrayOf(
        "maxNodes",
        "maxDepth",
        "maxInValues",
        "maxCidrBlocks",
        "maxLiteralBytes",
        "maxCost"
    )

    /** The budgets' members of `limits` (`CTL-40`, `CTL-41`). */
    internal val BUDGET_FIELDS = arrayOf(
        "maxPolicies",
        "maxStatements",
        "maxRoleBindings",
        "maxAnyoneBindings",
        "maxChangeOperations",
    )

    /** The types an external attribute can be declared with, by their document name. */
    @InternalCoSecApi
    val ATTRIBUTE_TYPES: Map<String, AttributeType> = linkedMapOf(
        "string" to AttributeType.STRING,
        "bool" to AttributeType.BOOL,
        "stringSet" to AttributeType.STRING_SET,
        "ip" to AttributeType.IP,
    )

    /** `limits` holding [limits], when set, and [budgets], when set. */
    internal fun limitsTree(limits: ConditionLimits?, budgets: DocumentBudgets?): ObjectNode {
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

    internal fun attributesTree(attributes: List<Attribute>): ArrayNode {
        val array = factory.arrayNode()
        attributes.forEach { attribute ->
            val type = ATTRIBUTE_TYPES.entries.first { it.value == attribute.type }.key
            array.addObject().put("path", attribute.path).put("type", type)
        }
        return array
    }

    private fun DocumentNode.limit(name: String, default: Int): Int {
        val field = field(name) ?: return default
        val value = field.long()
        if (value < 1 || value > Int.MAX_VALUE) field.fail("a limit is a positive int")
        return value.toInt()
    }

    /**
     * The condition limits of `limits`: null when it holds only budgets, so a document that sets only those reads back
     * as it was written; otherwise each absent one is its default, as an empty `limits` always was.
     */
    internal fun decodeLimits(node: DocumentNode): ConditionLimits? {
        node.requireObject(*LIMIT_FIELDS, *BUDGET_FIELDS)
        if (LIMIT_FIELDS.none { node.field(it) != null } && BUDGET_FIELDS.any { node.field(it) != null }) {
            return null
        }
        val default = ConditionLimits.DEFAULT
        return ConditionLimits(
            maxNodes = node.limit("maxNodes", default.maxNodes),
            maxDepth = node.limit("maxDepth", default.maxDepth),
            maxInValues = node.limit("maxInValues", default.maxInValues),
            maxCidrBlocks = node.limit("maxCidrBlocks", default.maxCidrBlocks),
            maxLiteralBytes = node.limit("maxLiteralBytes", default.maxLiteralBytes),
            maxCost = node.limit("maxCost", default.maxCost),
        )
    }

    /** The entry budgets of `limits`: null when it sets none, each absent one its default otherwise. */
    internal fun decodeBudgets(node: DocumentNode): DocumentBudgets? {
        node.requireObject(*LIMIT_FIELDS, *BUDGET_FIELDS)
        if (BUDGET_FIELDS.none { node.field(it) != null }) {
            return null
        }
        return DocumentBudgets(
            maxPolicies = node.limit("maxPolicies", DocumentBudgets.DEFAULT_MAX_POLICIES),
            maxStatements = node.limit("maxStatements", DocumentBudgets.DEFAULT_MAX_STATEMENTS),
            maxRoleBindings = node.limit("maxRoleBindings", DocumentBudgets.DEFAULT_MAX_ROLE_BINDINGS),
            maxAnyoneBindings = node.limit("maxAnyoneBindings", DocumentBudgets.DEFAULT_MAX_ANYONE_BINDINGS),
            maxChangeOperations = node.limit("maxChangeOperations", DocumentBudgets.DEFAULT_MAX_CHANGE_OPERATIONS),
        )
    }

    internal fun decodeAttributes(node: DocumentNode): List<Attribute> {
        // Unique paths, like unique ids, are the compiler's to check: a schema cannot say it.
        return node.elements().map { item ->
            item.requireObject("path", "type")
            val path = item.required("path")
            val text = path.text()
            if (!Attribute.isExternalPath(text)) {
                path.fail("an external attribute is ext.<provider>.<name>, not [$text]")
            }
            val type = item.required("type")
            val declared = ATTRIBUTE_TYPES[type.text()]
                ?: type.fail("an external attribute is one of ${ATTRIBUTE_TYPES.keys}, not [${type.text()}]")
            Attribute.external(text, declared)
        }
    }
}
