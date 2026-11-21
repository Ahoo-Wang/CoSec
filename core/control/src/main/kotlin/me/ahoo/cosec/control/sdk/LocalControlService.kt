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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpSupport.GET
import me.ahoo.cosec.control.api.ControlHttpSupport.POST
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration

/**
 * [ControlService] in the application's own process (`CTL-65`): each call is the control API's route itself, handed
 * to [ControlHttp] with the caller's request security, so it is authorized, recorded and answered as the route is, by
 * one code path. Only the routes of [ServiceRoutes] are reachable; a change is moved only once its own record says it
 * is a tenant document's. A [ControlCaller.service] caller is admitted by [IdentityGate.admit], the function the gate
 * reads a forwarded identity with, as a short-lived identity of the platform tenant.
 */
@InternalCoSecApi
class LocalControlService(
    private val http: ControlHttp,
    /** Admits a service caller as the gate admits a forwarded identity: same expiry, age, tenant and scope. */
    private val gate: IdentityGate,
    /** The control plane's app (`cosec.control.app-id`): a service caller's identity is in it. */
    private val app: AppId = PipelineControlAuthorizer.CONTROL_APP,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: IdGenerator = RandomIdGenerator,
) : ControlService {
    override suspend fun document(caller: ControlCaller, key: SnapshotKey): String =
        call(caller, GET, "/documents/${key(key)}")

    override suspend fun revisions(caller: ControlCaller, key: SnapshotKey, before: Long?, limit: Int?): String =
        call(
            caller,
            GET,
            "/documents/${key(key)}/revisions",
            listOfNotNull(before?.let { "before" to "$it" }, limit?.let { "limit" to "$it" }).toMap(),
        )

    override suspend fun revision(caller: ControlCaller, key: SnapshotKey, revision: Long): String =
        call(caller, GET, "/documents/${key(key)}/revisions/$revision")

    override suspend fun diff(caller: ControlCaller, key: SnapshotKey, from: Long, to: Long): String =
        call(caller, GET, "/documents/${key(key)}/diff", mapOf("from" to "$from", "to" to "$to"))

    override suspend fun apply(caller: ControlCaller, key: SnapshotKey, request: String): String =
        call(caller, POST, "/documents/${key(key)}:apply", body = request)

    override suspend fun propose(caller: ControlCaller, request: String): String =
        call(caller, POST, "/changes", body = request)

    override suspend fun change(caller: ControlCaller, id: String): String = call(
        caller,
        GET,
        "/changes/${segment(id)}"
    )

    override suspend fun changes(
        caller: ControlCaller,
        document: SnapshotKey,
        state: ChangeState?,
        after: String?,
        limit: Int?,
    ): String = call(
        caller,
        GET,
        "/changes",
        listOfNotNull(
            "document" to document.toString(),
            state?.let { "state" to it.name },
            after?.let { "after" to it },
            limit?.let { "limit" to "$it" },
        ).toMap(),
    )

    override suspend fun rebase(caller: ControlCaller, id: String): String = transition(caller, id, "rebase", "{}")

    override suspend fun startShadow(caller: ControlCaller, id: String): String = transition(caller, id, "shadow", "{}")

    override suspend fun skipShadow(caller: ControlCaller, id: String, reason: String): String = transition(
        caller,
        id,
        "skip-shadow",
        CoSecJson.mapper.writeValueAsString(CoSecJson.mapper.createObjectNode().put("reason", reason)),
    )

    override suspend fun abandon(caller: ControlCaller, id: String): String = transition(caller, id, "abandon", "{}")

    override suspend fun publish(caller: ControlCaller, id: String, request: String?): String =
        transition(caller, id, "publish", request ?: "{}")

    /** A step of change [id], once its record, read as [caller], says it is a tenant document's. */
    private suspend fun transition(caller: ControlCaller, id: String, verb: String, body: String): String {
        val security = security(caller)
        val key = CoSecJson.mapper.readTree(send(security, ControlHttpRequest(GET, "/changes/${segment(id)}")))
            .get("key")?.asString()
        if (key?.startsWith(TENANT) != true) throw refusal(http.refused(NOT_FOUND, security.requestId))
        return send(
            security,
            ControlHttpRequest(POST, "/changes/${segment(id)}:$verb", emptyMap(), body.encodeToByteArray())
        )
    }

    private suspend fun call(
        caller: ControlCaller,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String = send(security(caller), ControlHttpRequest(method, path, query, body?.encodeToByteArray()))

    /** [request] as its route answers it, unless it is outside [ServiceRoutes]: that is a 404, as no route. */
    private suspend fun send(security: RequestSecurity, request: ControlHttpRequest): String {
        if (!ServiceRoutes.allows(request)) throw refusal(http.refused(NOT_FOUND, security.requestId))
        val response = http.handle(request, security)
        if (response.status >= BAD_REQUEST) throw refusal(response)
        return response.body.decodeToString()
    }

    /** The request security [caller] acts with: its request's, or a service identity the gate admits now. */
    private fun security(caller: ControlCaller): RequestSecurity {
        caller.security?.let { return it }
        val principal = checkNotNull(caller.principal)
        val now = clock.instant()
        val requestId = ids.generate()
        val identity = Identity(
            tenant = TenantId.PLATFORM.value,
            subject = principal,
            app = app.value,
            space = "",
            roles = caller.roles,
            credential = Identity.Credential(Identity.Method.API_KEY, "service:$principal", now.plus(SERVICE_LIFETIME)),
            issuedAt = now,
            requestId = requestId,
        )
        return when (val admission = gate.admit(identity, requestId, FACTS)) {
            is IdentityGate.Admission.Admitted -> admission.security
            is IdentityGate.Admission.Refused -> throw refusal(http.refused(UNAUTHORIZED, requestId))
        }
    }

    private fun refusal(response: me.ahoo.cosec.control.api.ControlHttpResponse) =
        ControlRefusedException(response.status, response.body.decodeToString())

    private companion object {
        const val TENANT = "tenant:"
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val BYTE = 0xFF
        const val ASCII = 0x80
        const val UNRESERVED = "-._~"

        /** How long a service caller's identity lives: one call's, well under the gate's maximum age. */
        val SERVICE_LIFETIME: Duration = Duration.ofSeconds(30)

        /** The facts of an in-process call: no client, no headers; the gate reads its scope against no host app. */
        val FACTS = HttpFacts(
            method = POST,
            rawPath = "/",
            rawQuery = null,
            headers = Headers.EMPTY,
            cookies = Cookies.EMPTY,
            remoteAddress = "in-process",
            host = HostScope(null),
        )

        /** A document key as a path segment: its id escaped, so a `:` or `/` in it is part of the id. */
        fun key(key: SnapshotKey): String = when (key) {
            SnapshotKey.Platform -> "platform"
            is SnapshotKey.App -> "app:${segment(key.appId)}"
            is SnapshotKey.Tenant -> "$TENANT${segment(key.tenantId)}"
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
