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
import me.ahoo.cosec.control.sdk.WireJson.need
import me.ahoo.cosec.control.sdk.WireJson.only
import me.ahoo.cosec.control.sdk.WireJson.opt
import me.ahoo.cosec.control.sdk.WireJson.optText
import me.ahoo.cosec.control.sdk.WireJson.text
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** A change's operations and the request bodies that carry them, as the control API's routes take them (`CTL-76`). */
internal object OperationJson {
    /** The members of each operation the typed layer writes and reads: the control plane's (`OperationCodec`). */
    val MEMBERS: Map<String, Set<String>> = mapOf(
        "putPolicy" to setOf("op", "policy", "ifMatch"),
        "removePolicy" to setOf("op", "id", "ifMatch"),
        "putBinding" to setOf("op", "binding", "ifMatch"),
        "removeBinding" to setOf("op", "id", "ifMatch"),
        "putRole" to setOf("op", "role", "ifMatch"),
        "removeRole" to setOf("op", "id", "ifMatch"),
        "putRateLimit" to setOf("op", "rateLimit", "ifMatch"),
        "removeRateLimit" to setOf("op", "id", "ifMatch"),
        "putCatalog" to setOf("op", "catalog"),
    )

    fun operation(operation: Operation): ObjectNode {
        if (operation is Operation.Other) return WireJson.parse(operation.json) as ObjectNode
        val node = WireJson.objectNode().put("op", operation.op)
        when (operation) {
            is Operation.PutPolicy -> node.set("policy", DocumentJson.policy(operation.policy))
            is Operation.RemovePolicy -> node.put("id", operation.id)
            is Operation.PutBinding -> node.set("binding", DocumentJson.binding(operation.binding))
            is Operation.RemoveBinding -> node.put("id", operation.id)
            is Operation.PutRole -> node.set("role", DocumentJson.role(operation.role))
            is Operation.RemoveRole -> node.put("id", operation.id)
            is Operation.PutRateLimit -> node.set("rateLimit", ValueJson.rateLimit(operation.rule))
            is Operation.RemoveRateLimit -> node.put("id", operation.id)
            is Operation.PutCatalog -> operation.catalog?.let { node.set("catalog", DocumentJson.catalog(it)) }
            is Operation.Other -> error("written above")
        }
        operation.ifMatch?.let { node.put("ifMatch", it) }
        return node
    }

    fun operation(node: JsonNode): Operation {
        val op = node.text("op")
        val members = MEMBERS[op] ?: return Operation.Other(op, WireJson.write(node))
        node.only("$op operation", members)
        val ifMatch = node.optText("ifMatch")
        return when (op) {
            "putPolicy" -> Operation.PutPolicy(DocumentJson.policy(node.need("policy")), ifMatch)
            "removePolicy" -> Operation.RemovePolicy(node.text("id"), ifMatch)
            "putBinding" -> Operation.PutBinding(DocumentJson.binding(node.need("binding")), ifMatch)
            "removeBinding" -> Operation.RemoveBinding(node.text("id"), ifMatch)
            "putRole" -> Operation.PutRole(DocumentJson.role(node.need("role")), ifMatch)
            "removeRole" -> Operation.RemoveRole(node.text("id"), ifMatch)
            "putRateLimit" -> Operation.PutRateLimit(RateLimitRule.of(WireJson.write(node.need("rateLimit"))), ifMatch)
            "removeRateLimit" -> Operation.RemoveRateLimit(node.text("id"), ifMatch)
            else -> Operation.PutCatalog(node.opt("catalog")?.let(DocumentJson::catalog))
        }
    }

    /** The body of `POST /changes`. */
    fun request(request: ChangeRequest): String = WireJson.write(
        WireJson.objectNode().apply {
            put("document", request.document.toString())
            put("base", request.base)
            put("note", request.note)
            putArray("operations").apply { request.operations.forEach { add(operation(it)) } }
        },
    )

    /** The body of `POST /documents/{key}:apply`. */
    fun request(request: ApplyRequest): String = WireJson.write(
        WireJson.objectNode().apply {
            put("mode", request.mode.text)
            put("note", request.note)
            set("document", DocumentJson.document(request.content, request.base))
        },
    )

    /** The body of `POST /changes/{id}:publish`: none without an [ack]. */
    fun publish(ack: ShadowAck?): String? = ack?.let {
        WireJson.write(
            WireJson.objectNode().apply {
                putObject("ack").apply {
                    put("window", it.window)
                    putObject("counts").apply { it.counts.forEach { (outcome, count) -> put(outcome, count) } }
                }
            },
        )
    }
}
