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

package me.ahoo.cosec.control.audit

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * A change event as JSON (RFC 0007 §10.5), as an audit sink stores it: no document content, only what the event
 * carries.
 */
@InternalCoSecApi
object ChangeEventJson {
    private val factory = tools.jackson.databind.node.JsonNodeFactory.instance

    fun write(event: ChangeEvent): String {
        val root = factory.objectNode()
            .put("id", event.id)
            .put("time", event.time.toString())
            .put("kind", event.kind.name)
            .put("change", event.change.value)
            .put("document", event.document.toString())
            .put("note", event.note)
        root.putObject(
            "operator"
        ).put("tenant", event.operator.tenant.value).put("principal", event.operator.principal.value)
        event.base?.let { root.putObject("base").put("revision", it.revision).put("digest", it.digest) }
        event.candidate?.let { root.putObject("candidate").put("revision", it.revision).put("digest", it.digest) }
        root.putArray("escalations").apply {
            event.escalations.forEach {
                addObject().put(
                    "subject",
                    it.subject
                ).put("reason", it.reason).put("detail", it.detail)
            }
        }
        root.putArray("confirmations").apply {
            event.confirmations.forEach {
                addObject().put(
                    "policy",
                    it.policy.value
                ).put("statement", it.statement).put("kind", it.kind).put("digest", it.digest)
            }
        }
        if (event.moved.isNotEmpty()) {
            root.putArray("moved").apply {
                event.moved.forEach {
                    addObject().put("document", it.document).put("checked", it.checked)
                        .put("current", it.current).put("readable", it.readable)
                }
            }
        }
        event.shadow?.let { shadow ->
            val node = root.putObject("shadow").put("closedAt", shadow.closedAt.toString())
                .put("insufficient", shadow.insufficient).put("blocked", shadow.blocked)
            node.putObject(
                "counts"
            ).apply { shadow.counts.toSortedMap().forEach { (outcome, count) -> put(outcome, count) } }
        }
        return me.ahoo.cosec.document.json.CoSecJson.mapper.writeValueAsString(root)
    }
}
