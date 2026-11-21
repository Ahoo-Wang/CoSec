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

package me.ahoo.cosec.system

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The `CoSec-Identity` header of a principal a service states, written from its documented wire format (base64url of
 * compact JSON, `schema/8.0/cosec-identity.schema.json`) with nothing of CoSec's, as [ForwardedIdentity] reads one: the
 * scenarios check what the control-plane host believes, not what CoSec's own writer produces. Every header is made now
 * (the host refuses an identity older than a minute), so a scenario that wants an old or an expired one says so.
 */
object StatedIdentity {
    /** The platform tenant: a service acts in it, as every principal that is not a tenant's. */
    const val PLATFORM = "(platform)"

    @Suppress("LongParameterList")
    fun header(
        subject: String,
        tenant: String = PLATFORM,
        app: String = SignIn.CONTROL_APP,
        method: String = "api-key",
        jti: String = subject,
        roles: List<String> = emptyList(),
        issuedAt: Instant = Instant.now(),
        credentialExpiresAt: Instant = Instant.now().plus(Duration.ofMinutes(1)),
    ): String {
        val identity = linkedMapOf(
            "v" to 1,
            "tenant" to tenant,
            "sub" to subject,
            "scope" to linkedMapOf("app" to app, "space" to ""),
            "roles" to roles,
            "auth" to linkedMapOf(
                "method" to method,
                "jti" to jti,
                "exp" to credentialExpiresAt.epochSecond,
                "iat" to issuedAt.epochSecond,
            ),
            "iat" to issuedAt.epochSecond,
            "rid" to "system" + UUID.randomUUID().toString().replace("-", ""),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.mapper.writeValueAsBytes(identity))
    }
}

/**
 * The control API as a service calls it: straight to a control-plane host, not through a gateway, stating the principal it
 * acts as in a `CoSec-Identity` header ([StatedIdentity]). [root] is the host's service port (8082) for the narrow profile a
 * service reaches, or its operators' port (8080) to show what the same stated principal gets there.
 */
class ServiceControl(
    private val root: URI,
    private val subject: String,
    private val tenant: String = StatedIdentity.PLATFORM,
    private val method: String = "api-key",
    /** The credential's id as the caller states it, which the record keeps as `via.credential`. */
    private val jti: String = subject,
    /** Headers a caller states besides the identity, none of which the host may believe (the peer is the connection's). */
    private val extra: List<Pair<String, String>> = emptyList(),
) {
    private fun uri(path: String): URI = URI.create("$root/cosec/control/v1$path")

    private fun headers(): List<Pair<String, String>> =
        listOf("CoSec-Identity" to StatedIdentity.header(subject, tenant, method = method, jti = jti)) + extra

    fun get(path: String): Exchange = Http.send("GET", uri(path), headers(), null)

    fun post(path: String, body: Any = emptyMap<String, Any?>()): Exchange =
        Http.send("POST", uri(path), headers() + ("Content-Type" to "application/json"), Json.mapper.writeValueAsString(body))

    fun document(key: String): Exchange = get("/documents/$key")

    /** The revision [key]'s head is at. */
    fun revision(key: String): Long = (document(key).ok().json().getValue("revision") as Number).toLong()

    fun propose(key: String, base: Long, operations: List<Map<String, Any?>>, note: String): Exchange =
        post("/changes", mapOf("document" to key, "base" to base, "note" to note, "operations" to operations))

    fun change(id: String): Exchange = get("/changes/$id")

    /** A step of a change: `shadow`, `skip-shadow`, `approve`, `publish`, ... */
    fun step(id: String, verb: String, body: Map<String, Any?> = emptyMap()): Exchange = post("/changes/$id:$verb", body)

    /** The same principal as another that states the same: this one on [other]'s port. */
    fun on(other: URI): ServiceControl = ServiceControl(other, subject, tenant, method, jti, extra)

    companion object {
        /** An operation that puts a policy of one statement allowing GET of [path]: a change that grants nobody anything. */
        fun putPolicy(id: String, path: String = "/behind/$id/**"): Map<String, Any?> = mapOf(
            "op" to "putPolicy",
            "policy" to mapOf(
                "id" to id,
                "statements" to listOf(
                    mapOf("name" to "pages", "effect" to "allow", "action" to "http:GET", "resource" to listOf("http:path:$path")),
                ),
            ),
        )

        /** An operation that binds [policy] to [role] in every app and space: what makes a change a grant. */
        fun bindRole(policy: String, role: String): Map<String, Any?> = mapOf(
            "op" to "putBinding",
            "binding" to mapOf(
                "id" to "$role-$policy",
                "subject" to mapOf("role" to role),
                "app" to "*",
                "space" to "*",
                "policy" to policy,
            ),
        )
    }
}
