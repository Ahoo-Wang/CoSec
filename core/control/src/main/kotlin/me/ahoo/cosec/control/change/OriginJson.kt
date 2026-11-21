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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * A [ChangeOrigin] as JSON, in the stored change and in the event (`CTL-57`): `{"asserted":true,"via":{"method":...,
 * "credential":...,"request":...},"peer":...}`, with the members it has. Additive: a reader that does not know the member
 * ignores it.
 */
@InternalCoSecApi
@OptIn(ExperimentalCoSecApi::class)
internal object OriginJson {
    fun write(node: ObjectNode, origin: ChangeOrigin): ObjectNode {
        node.put("asserted", origin.asserted)
        origin.via?.let { via ->
            val out = node.putObject("via").put("method", via.method)
            via.credentialId?.let { out.put("credential", it) }
            via.requestId?.let { out.put("request", it) }
        }
        origin.peer?.let { node.put("peer", it) }
        return node
    }

    fun read(node: JsonNode): ChangeOrigin = ChangeOrigin(
        asserted = node.get("asserted")?.asBoolean() ?: false,
        via = node.get("via")?.takeIf { it.isObject }?.let { via ->
            ChangeOrigin.Via(
                method = via.get("method")?.asString().orEmpty(),
                credentialId = via.get("credential")?.asString(),
                requestId = via.get("request")?.asString(),
            )
        },
        peer = node.get("peer")?.asString(),
    )
}
