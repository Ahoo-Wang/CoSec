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
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** What more than one part of the change codec needs: the node factory, an actor and the strictness helpers. */
internal object ChangeJson {
    internal val factory = JsonNodeFactory.instance

    internal fun actorTree(actor: Actor): ObjectNode =
        factory.objectNode().put("tenant", actor.tenant.value).put("principal", actor.principal.value)

    internal fun actor(node: JsonNode): Actor {
        node.only("tenant", "principal")
        return Actor(TenantId(node.need("tenant").text()), PrincipalId(node.need("principal").text()))
    }

    internal fun JsonNode.need(name: String): JsonNode = get(name) ?: malformed("[$name] is missing")

    internal fun JsonNode.text(): String = if (isString) asString() else malformed("$this is not a string")

    internal fun JsonNode.only(vararg names: String) {
        if (!isObject) malformed("$this is not an object")
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        if (unknown.isNotEmpty()) malformed("unknown fields $unknown")
    }

    /** [text], stored content quoted in a message, with its control characters escaped (RC1-15). */
    internal fun printable(text: String?): String = SnapshotKeyText.printable(text.orEmpty())

    internal fun malformed(reason: String): Nothing = throw SnapshotFormatException(
        "A change request is malformed: ${printable(reason)}."
    )
}
