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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.compat.side

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.control.api.ChangeAnswer
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.sdk.Binding
import me.ahoo.cosec.control.sdk.ChangeRequest
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.control.sdk.Effect
import me.ahoo.cosec.control.sdk.Operation
import me.ahoo.cosec.control.sdk.Policy
import me.ahoo.cosec.control.sdk.Resource
import me.ahoo.cosec.control.sdk.Statement
import me.ahoo.cosec.control.sdk.Subject
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.lang.reflect.Proxy

/**
 * The control API between an application's `control-sdk` and a control plane of another version (MIX-16, MIX-17):
 * `sdk.propose` is the request body this version's typed client writes for a sample change; `plane.operations` reads a
 * request body's operations as this version's control plane does, strictly, and answers them as it would store them;
 * `plane.answer` is the answer this version's control plane gives for a stored change; `sdk.change` is what this
 * version's typed client reads of an answer.
 */
internal object ControlApiOperations {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "sdk.propose" to { _ ->
            val request = ChangeRequest(
                SnapshotKey.Tenant("acme"), 1,
                listOf(
                    Operation.PutPolicy(
                        Policy("orders", listOf(Statement(Effect.ALLOW, listOf("order:read"), listOf(Resource("order/**"))))),
                    ),
                    Operation.PutBinding(Binding("b1", Subject.Anyone, "order", "", "orders")),
                    Operation.RemoveRateLimit("r1"),
                ),
                "sample",
            )
            val sent = arrayOfNulls<String>(1)
            try {
                runBlocking { ControlClient(service(propose = { sent[0] = it; throw Captured() })).propose(CALLER, request) }
            } catch (_: Captured) {
                // The body was captured; the answer is another operation's.
            }
            checkNotNull(sent[0])
        },
        "plane.operations" to { a ->
            val key = DocumentOperations.key(a.getValue("key"))
            val operations = CoSecJson.mapper.readTree(a.getValue("body")).get("operations")
            // Read as a request is (strictly), then as this version stores them: what its control plane understood.
            operations.values().forEach { ChangeRequestCodec.operation(key, it) }
            val record = CoSecJson.mapper.readTree(STORED) as tools.jackson.databind.node.ObjectNode
            record.put("key", a.getValue("key")).set("operations", operations)
            val stored = ChangeRequestCodec.encode(ChangeRequestCodec.decode(CoSecJson.mapper.writeValueAsBytes(record)))
            CoSecJson.mapper.readTree(stored).get("operations").values().joinToString("\n") { it.toString() }
        },
        "plane.answer" to { a ->
            ChangeAnswer.encode(ChangeRequestCodec.decode(a.getValue("record").encodeToByteArray()), null, false).decodeToString()
        },
        "sdk.change" to { a ->
            runBlocking { ControlClient(service(answer = a.getValue("answer"))).change(CALLER, "c1") }.toString()
        },
        "sdk.document" to { a ->
            runBlocking {
                ControlClient(service(answer = a.getValue("answer"))).document(CALLER, DocumentOperations.key(a.getValue("key")))
            }.toString()
        },
    )

    private class Captured : RuntimeException(null, null, false, false)

    /** A stored change to carry operations through this version's change codec. */
    private const val STORED = """{"format":1,"id":"c1","key":"tenant:acme","base":{"revision":0},"operations":[],""" +
        """"author":{"tenant":"acme","principal":"alice"},"note":"n","kind":"PUBLISH","state":"DRAFT","findings":[],""" +
        """"checkedWith":{},"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}"""

    /** A [ControlService] answering [answer] to a read of a change or a document and capturing a proposal. */
    private fun service(propose: (String) -> String = { error("no proposal") }, answer: String = ""): ControlService =
        Proxy.newProxyInstance(ControlService::class.java.classLoader, arrayOf(ControlService::class.java)) { _, method, args ->
            when (method.name) {
                "propose" -> propose(args[1] as String)
                "change", "document" -> answer
                else -> error("not this test's call: ${method.name}")
            }
        } as ControlService

    private val CALLER: ControlCaller = ControlCaller.of(
        object : SecurityContext {
            override val tenantId = "acme"
            override val principal = object : CoSecPrincipal {
                override val id = "alice"
                override val authenticated = true
                override val attributes = emptyMap<String, Any>()
            }
            override val scope = object : SecurityScope {
                override val appId = "cosec-control"
                override val spaceId = ""
                override val roles = listOf("admin")
            }
            override val forwardedIdentity: ForwardedIdentity? = null
        },
    )
}
