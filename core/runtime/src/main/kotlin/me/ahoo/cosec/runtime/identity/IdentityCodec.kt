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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityFormatException.Reason
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.Base64

/**
 * The [Identity] header, format v1: base64url (no padding) of compact JSON with exactly these members, in this order:
 *
 * ```json
 * {"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""},"roles":["clerk"],
 *  "auth":{"method":"jwt","jti":"0TEC7","exp":1793404800},"iat":1793401200,"rid":"0TEC7Hw00001zR0",
 *  "claims":{"name":"Alice"}}
 * ```
 *
 * Times are epoch seconds, from 0 to the end of year 9999. `claims` may be absent; `auth.exp` may not: a gateway
 * forwards no identity whose credential never expires, and a service refuses one without it. Decoding is strict: an unknown member, a member of
 * the wrong type, a duplicate, an empty id or a header over the size limit is refused, never read as anonymous. The
 * `cosec-identity` format descriptor (`schema/cosec-identity.schema.json`) describes the JSON.
 */
object IdentityCodec {
    /** The header the gateway sets on a permitted request, and strips from every request it receives. */
    const val HEADER = "CoSec-Identity"

    const val VERSION = 1

    /** The largest header, encoded, a gateway sends and a service accepts by default. */
    const val DEFAULT_MAX_BYTES = 8 * 1024

    /** The longest string claim value a gateway forwards. */
    const val MAX_CLAIM_LENGTH = 1024

    /** 9999-12-31T23:59:59Z: later seconds are refused, so no time arithmetic on a decoded identity can overflow. */
    private const val MAX_SECONDS = 253_402_300_799L

    private val FIELDS = arrayOf("v", "tenant", "sub", "scope", "roles", "auth", "iat", "rid", "claims")
    private val SCOPE_FIELDS = arrayOf("app", "space")
    private val AUTH_FIELDS = arrayOf("method", "jti", "exp")

    private val reader: ObjectReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val factory = JsonNodeFactory.instance
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** The header value of [identity]; [IdentityOverflowException] when it is longer than [maxBytes] or a claim is. */
    fun encode(identity: Identity, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        val header = encoder.encodeToString(CoSecJson.mapper.writeValueAsBytes(tree(identity)))
        if (header.length > maxBytes) {
            throw IdentityOverflowException(
                "The identity of [${identity.subject}] is ${header.length} bytes encoded, over the limit of $maxBytes: " +
                    "${identity.roles.size} roles, ${identity.claims.size} claims."
            )
        }
        return header
    }

    /** [header] as an identity; [IdentityFormatException] when it is not one this service reads. */
    @Suppress("ThrowsCount")
    fun decode(header: String, maxBytes: Int = DEFAULT_MAX_BYTES): Identity {
        if (header.length > maxBytes) {
            throw IdentityFormatException(
                Reason.OVERSIZE,
                "The identity header is ${header.length} bytes, over $maxBytes."
            )
        }
        val bytes = try {
            decoder.decode(header)
        } catch (error: IllegalArgumentException) {
            throw malformed("not base64url", error)
        }
        if (header.endsWith('=')) {
            throw malformed("padded")
        }
        return decodeJson(bytes)
    }

    /** The JSON of an identity, before base64url: what the format descriptor describes. */
    @Suppress("ThrowsCount")
    fun decodeJson(bytes: ByteArray): Identity {
        val root = try {
            reader.readTree(bytes) ?: throw malformed("empty")
        } catch (error: JacksonException) {
            throw malformed("not JSON: ${error.originalMessage}", error)
        }
        root.only("$", *FIELDS)
        val version = root.need("v")
        if (!version.isIntegralNumber || version.asLong() != VERSION.toLong()) {
            throw IdentityFormatException(
                Reason.UNSUPPORTED_VERSION,
                "The identity header is version $version, not $VERSION."
            )
        }
        val scope = root.need("scope").also { it.only("$.scope", *SCOPE_FIELDS) }
        val auth = root.need("auth").also { it.only("$.auth", *AUTH_FIELDS) }
        val method = auth.need("method").text("$.auth.method")
        return Identity(
            tenant = root.need("tenant").id("$.tenant"),
            subject = root.need("sub").id("$.sub"),
            app = scope.need("app").text("$.scope.app"),
            space = scope.need("space").text("$.scope.space"),
            roles = roles(root.need("roles")),
            credential = Identity.Credential(
                method = method.takeIf { it.isNotEmpty() }?.let(Identity::Method) ?: throw malformed("$.auth.method is empty"),
                id = auth.need("jti").id("$.auth.jti"),
                expiresAt = (
                    auth.get("exp") ?: throw IdentityFormatException(Reason.UNBOUNDED, "The identity has no auth.exp.")
                    ).seconds("$.auth.exp"),
            ),
            issuedAt = root.need("iat").seconds("$.iat"),
            requestId = root.need("rid").id("$.rid"),
            claims = root.get("claims")?.let(::claims).orEmpty(),
        )
    }

    private fun tree(identity: Identity): ObjectNode {
        val root = factory.objectNode().put("v", VERSION)
        root.put("tenant", identity.tenant).put("sub", identity.subject)
        root.putObject("scope").put("app", identity.app).put("space", identity.space)
        root.putArray("roles").apply { identity.roles.sorted().forEach(::add) }
        root.putObject("auth").apply {
            put("method", identity.credential.method.value)
            put("jti", identity.credential.id)
            val expiresAt = identity.credential.expiresAt ?: throw IdentityOverflowException(
                "The credential of [${identity.subject}] never expires: a service could not bound the identity."
            )
            put("exp", expiresAt.epochSecond)
        }
        root.put("iat", identity.issuedAt.epochSecond).put("rid", identity.requestId)
        if (identity.claims.isNotEmpty()) {
            val claims = root.putObject("claims")
            identity.claims.toSortedMap().forEach { (name, value) -> claim(claims, name, value) }
        }
        return root
    }

    private fun claim(claims: ObjectNode, name: String, value: Any) {
        when (value) {
            is String -> {
                if (value.length > MAX_CLAIM_LENGTH) {
                    throw IdentityOverflowException(
                        "Claim [$name] is ${value.length} characters, over $MAX_CLAIM_LENGTH."
                    )
                }
                claims.put(name, value)
            }
            is Boolean -> claims.put(name, value)
            is Int -> claims.put(name, value)
            is Long -> claims.put(name, value)
            is Double -> claims.put(name, value)
            else -> throw IdentityOverflowException(
                "Claim [$name] is a ${value::class.simpleName}, not a string, number or boolean."
            )
        }
    }

    private fun roles(node: JsonNode): Set<String> {
        if (!node.isArray) throw malformed("$.roles is not an array")
        val roles = LinkedHashSet<String>()
        node.values().forEachIndexed { i, role ->
            if (!roles.add(role.id("$.roles[$i]"))) throw malformed("$.roles[$i] repeats a role")
        }
        return roles
    }

    @Suppress("ThrowsCount")
    private fun claims(node: JsonNode): Map<String, Any> {
        if (!node.isObject) throw malformed("$.claims is not an object")
        return node.properties().associateTo(LinkedHashMap()) { (name, value) ->
            if (name.isEmpty()) throw malformed("$.claims has an empty name")
            name to when {
                value.isString -> value.asString().also {
                    if (it.length > MAX_CLAIM_LENGTH) throw malformed("$.claims.$name is over $MAX_CLAIM_LENGTH characters")
                }
                value.isBoolean -> value.booleanValue()
                value.isIntegralNumber -> value.longValue()
                value.isNumber -> value.doubleValue()
                else -> throw malformed("$.claims.$name is not a string, number or boolean")
            }
        }
    }

    private fun JsonNode.only(path: String, vararg names: String) {
        if (!isObject) throw malformed("$path is not an object")
        val unknown = properties().map { it.key }.filter { it !in names }
        if (unknown.isNotEmpty()) throw malformed("$path has unknown members $unknown")
    }

    private fun JsonNode.need(name: String): JsonNode = get(name) ?: throw malformed("[$name] is missing")

    private fun JsonNode.text(path: String): String =
        if (isString) asString() else throw malformed("$path is not a string")

    private fun JsonNode.id(path: String): String = text(path).ifEmpty { throw malformed("$path is empty") }

    private fun JsonNode.seconds(path: String): Instant {
        if (!isIntegralNumber || !canConvertToLong() || longValue() !in 0..MAX_SECONDS) {
            throw malformed("$path is not epoch seconds up to year 9999")
        }
        return Instant.ofEpochSecond(longValue())
    }

    private fun malformed(reason: String, cause: Throwable? = null) =
        IdentityFormatException(Reason.MALFORMED, "The identity header is malformed: $reason.", cause)
}
