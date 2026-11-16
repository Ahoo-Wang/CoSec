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

import me.ahoo.cosec.control.change.ChangeJson.actor
import me.ahoo.cosec.control.change.ChangeJson.actorTree
import me.ahoo.cosec.control.change.ChangeJson.factory
import me.ahoo.cosec.control.change.ChangeJson.malformed
import me.ahoo.cosec.control.change.ChangeJson.need
import me.ahoo.cosec.control.change.ChangeJson.only
import me.ahoo.cosec.control.change.ChangeJson.text
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** An approval and what it records of its approver: memberships and attributes, both ways. */
internal object ApprovalCodec {
    /** Every field, always: `null` where the approver held no memberships or its credential did not say (RC1-11). */
    internal fun tree(node: ObjectNode, approval: Approval) {
        node.set("approver", actorTree(approval.approver))
        node.put("at", approval.at.toString()).put("candidate", approval.candidate)
        node.putArray("covers").apply { approval.covers.forEach { add(it) } }
        membershipsTree(node, approval.memberships)
        val attributes = node.putObject("attributes")
        approval.attributes.forEach { (name, value) -> attributes.set(name, attributeTree(value)) }
        node.put("principalBindings", approval.principalBindings).put("breakGlass", approval.breakGlass)
        node.set("credentialId", approval.credentialId?.let(factory::stringNode) ?: factory.nullNode())
        node.set("issuedAt", approval.issuedAt?.let { factory.stringNode(it.toString()) } ?: factory.nullNode())
    }

    private fun membershipsTree(node: ObjectNode, memberships: Memberships?) {
        if (memberships == null) {
            node.putNull("memberships")
            return
        }
        val written = node.putObject("memberships")
        val roles = written.putObject("roles")
        memberships.roles.forEach { (space, spaceRoles) ->
            roles.putArray(space.value).apply { spaceRoles.forEach { add(it.value) } }
        }
        written.putArray("attachments").apply { memberships.attachments.forEach { add(it.value) } }
        written.put("source", memberships.source.name)
    }

    /**
     * An approver's attribute as JSON (round-2 review R2): of its own type where JSON has one, so that it reads back
     * as the same value; anything else as the text the engine reads of it (`subject.attributes.<name>` is its string).
     */
    private fun attributeTree(value: Any?): JsonNode = when (value) {
        null -> factory.nullNode()
        is Map<*, *> -> factory.objectNode().apply {
            value.forEach { (name, item) -> set(name.toString(), attributeTree(item)) }
        }
        is Iterable<*> -> factory.arrayNode().apply { value.forEach { add(attributeTree(it)) } }
        is Array<*> -> factory.arrayNode().apply { value.forEach { add(attributeTree(it)) } }
        else -> scalarTree(value)
    }

    private fun scalarTree(value: Any): JsonNode = when (value) {
        is String -> factory.stringNode(value)
        is Boolean -> factory.booleanNode(value)
        is Int, is Short, is Byte -> factory.numberNode((value as Number).toInt())
        is Long -> factory.numberNode(value)
        is Float -> factory.numberNode(value)
        is Double -> factory.numberNode(value)
        is java.math.BigInteger -> factory.numberNode(value)
        is java.math.BigDecimal -> factory.numberNode(value)
        else -> factory.stringNode(value.toString())
    }

    private fun attribute(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isString -> node.text()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> if (node.canConvertToLong()) node.longValue() else node.bigIntegerValue()
        node.isBigDecimal -> node.decimalValue()
        node.isFloatingPointNumber -> node.doubleValue()
        node.isArray -> node.values().map(::attribute)
        node.isObject -> node.properties().associate { (name, item) -> name to attribute(item) }
        else -> throw SnapshotFormatException("An approver's attribute is not a JSON value: $node")
    }

    private fun memberships(node: JsonNode): Memberships {
        node.only("roles", "attachments", "source")
        val roles = LinkedHashMap<SpaceId, Set<RoleId>>()
        node.need("roles").properties().forEach { (space, spaceRoles) ->
            roles[SpaceId(space)] = spaceRoles.values().mapTo(LinkedHashSet()) { RoleId(it.text()) }
        }
        return Memberships(
            Memberships.Source.valueOf(node.need("source").text()),
            roles,
            node.need("attachments").values().map { PolicyId(it.text()) },
        )
    }

    internal fun decode(node: JsonNode): Approval {
        node.only(
            "approver",
            "at",
            "candidate",
            "covers",
            "memberships",
            "attributes",
            "principalBindings",
            "breakGlass",
            "credentialId",
            "issuedAt",
        )
        return Approval(
            actor(node.need("approver")),
            Instant.parse(node.need("at").text()),
            node.need("candidate").text(),
            node.need("covers").values().map { covered -> covered.text() },
            node.need("memberships").takeUnless { it.isNull }?.let(::memberships),
            node.need("attributes").members().mapNotNull { (name, value) ->
                attribute(value)?.let { read -> name to read }
            }.toMap(),
            principalBindings = node.need("principalBindings").flag(),
            breakGlass = node.need("breakGlass").flag(),
            credentialId = node.need("credentialId").textOrNull(),
            issuedAt = node.need("issuedAt").textOrNull()?.let(Instant::parse),
        )
    }

    private fun JsonNode.members() = if (isObject) properties() else malformed("$this is not an object")

    private fun JsonNode.flag(): Boolean = if (isBoolean) booleanValue() else malformed("$this is not a boolean")

    private fun JsonNode.textOrNull(): String? = if (isNull) null else text()
}
