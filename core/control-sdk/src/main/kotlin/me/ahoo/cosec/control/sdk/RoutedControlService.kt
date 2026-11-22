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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * [ControlService] as the control API's routes take each call (`CTL-77`): every call is made into one [ServiceCall]
 * here, and an implementation only says how a call reaches the control plane, in process or over the network. So the two
 * implementations cannot differ in what they ask, only in how they carry it.
 */
@InternalCoSecApi
abstract class RoutedControlService : ControlService {
    /** [call] made as [caller]: the route's answer, or its refusal as a [ControlRefusedException]. */
    protected abstract suspend fun call(caller: ControlCaller, call: ServiceCall): String

    override suspend fun documents(caller: ControlCaller, kind: DocumentKind, after: String?, limit: Int?): String =
        call(
            caller,
            ServiceCall(
                GET,
                "/documents",
                listOfNotNull(
                    "kind" to kind.name.lowercase(),
                    after?.let { "after" to it },
                    limit?.let { "limit" to "$it" },
                ).toMap(),
            ),
        )

    override suspend fun document(caller: ControlCaller, key: SnapshotKey): String =
        call(caller, ServiceCall(GET, "/documents/${key(key)}"))

    override suspend fun revisions(caller: ControlCaller, key: SnapshotKey, before: Long?, limit: Int?): String = call(
        caller,
        ServiceCall(
            GET,
            "/documents/${key(key)}/revisions",
            listOfNotNull(before?.let { "before" to "$it" }, limit?.let { "limit" to "$it" }).toMap(),
        ),
    )

    override suspend fun revision(caller: ControlCaller, key: SnapshotKey, revision: Long): String =
        call(caller, ServiceCall(GET, "/documents/${key(key)}/revisions/$revision"))

    override suspend fun diff(caller: ControlCaller, key: SnapshotKey, from: Long, to: Long): String =
        call(caller, ServiceCall(GET, "/documents/${key(key)}/diff", mapOf("from" to "$from", "to" to "$to")))

    override suspend fun apply(caller: ControlCaller, key: SnapshotKey, request: String): String =
        call(caller, ServiceCall(POST, "/documents/${key(key)}:apply", body = request))

    override suspend fun propose(caller: ControlCaller, request: String): String =
        call(caller, ServiceCall(POST, "/changes", body = request))

    override suspend fun check(caller: ControlCaller, request: String): String =
        call(caller, ServiceCall(POST, "/changes:check", body = request))

    override suspend fun checkApply(caller: ControlCaller, key: SnapshotKey, request: String): String =
        call(caller, ServiceCall(POST, "/documents/${key(key)}:check", body = request))

    override suspend fun change(caller: ControlCaller, id: String): String =
        call(caller, ServiceCall(GET, "/changes/${segment(id)}"))

    override suspend fun changes(
        caller: ControlCaller,
        document: SnapshotKey?,
        state: ChangeState?,
        after: String?,
        limit: Int?,
    ): String = call(
        caller,
        ServiceCall(
            GET,
            "/changes",
            listOfNotNull(
                document?.let { "document" to it.toString() },
                state?.let { "state" to it.text },
                after?.let { "after" to it },
                limit?.let { "limit" to "$it" },
            ).toMap(),
        ),
    )

    override suspend fun rebase(caller: ControlCaller, id: String): String = step(caller, id, "rebase", EMPTY)

    override suspend fun startShadow(caller: ControlCaller, id: String): String = step(caller, id, "shadow", EMPTY)

    override suspend fun skipShadow(caller: ControlCaller, id: String, reason: String): String = step(
        caller,
        id,
        "skip-shadow",
        CoSecJson.mapper.writeValueAsString(CoSecJson.mapper.createObjectNode().put("reason", reason)),
    )

    override suspend fun abandon(caller: ControlCaller, id: String): String = step(caller, id, "abandon", EMPTY)

    override suspend fun publish(caller: ControlCaller, id: String, request: String?): String =
        step(caller, id, "publish", request ?: EMPTY)

    /** A step of change [id]: the service profile serves it only for a tenant document's change. */
    private suspend fun step(caller: ControlCaller, id: String, verb: String, body: String): String =
        call(caller, ServiceCall(POST, "/changes/${segment(id)}:$verb", body = body))

    @InternalCoSecApi
    companion object {
        const val GET = "GET"
        const val POST = "POST"
        private const val EMPTY = "{}"
        private const val BYTE = 0xFF
        private const val ASCII = 0x80
        private const val UNRESERVED = "-._~"

        /** A document key as a path segment: its id escaped, so a `:` or `/` in it is part of the id. */
        fun key(key: SnapshotKey): String = when (key) {
            SnapshotKey.Platform -> "platform"
            is SnapshotKey.App -> "app:${segment(key.appId)}"
            is SnapshotKey.Tenant -> "tenant:${segment(key.tenantId)}"
        }

        /** [text] percent-encoded but for the unreserved characters, as one path segment. */
        fun segment(text: String): String = buildString {
            text.encodeToByteArray().forEach { byte ->
                val char = (byte.toInt() and BYTE).toChar()
                val unreserved = char.code < ASCII && char.isLetterOrDigit() || char in UNRESERVED
                if (unreserved) append(char) else append("%%%02X".format(byte))
            }
        }
    }
}
