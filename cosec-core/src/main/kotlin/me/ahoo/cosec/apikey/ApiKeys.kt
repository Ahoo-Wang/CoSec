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

package me.ahoo.cosec.apikey

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenVerificationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * A machine identity: whoever presents the key acts as [principalId] with these roles and policies.
 *
 * Only the key's hash is ever stored (see [ApiKeys.hash]); the key itself is shown once, when it is created.
 *
 * @param expiresAt when the key stops working; null never expires
 */
data class ApiKey(
    val id: String,
    val principalId: String,
    val tenantId: String? = null,
    val roles: Set<String> = emptySet(),
    val policies: Set<String> = emptySet(),
    val attributes: Map<String, String> = emptyMap(),
    val expiresAt: Instant? = null,
    val enabled: Boolean = true
) {
    fun toPrincipal(): CoSecPrincipal {
        val principal = SimplePrincipal(
            id = principalId,
            policies = policies,
            roles = roles,
            attributes = attributes + (API_KEY_ID_ATTRIBUTE to id),
        )
        return tenantId?.let { SimpleTenantPrincipal(principal, SimpleTenant(it)) } ?: principal
    }

    companion object {
        /** Principal attribute naming the key a request authenticated with. */
        const val API_KEY_ID_ATTRIBUTE = "apiKeyId"
    }
}

/**
 * Looks up API keys by hash. Called while parsing the security context: an implementation that blocks (a database
 * or Redis lookup) needs the parse to run off the event loop (`cosec.api-key.store-blocking=true` in the starter).
 */
fun interface ApiKeyStore {
    fun findByHash(hash: String): ApiKey?
}

/**
 * @param keys API keys by hash
 */
class InMemoryApiKeyStore(private val keys: Map<String, ApiKey>) : ApiKeyStore {
    override fun findByHash(hash: String): ApiKey? = keys[hash]
}

object ApiKeys {
    private const val KEY_BYTES = 32
    private const val PREFIX = "cosec_"
    private val random = SecureRandom()

    /** The SHA-256 of [key], hex-encoded: what a store keeps instead of the key. */
    fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A new random key. Show it once and store only its [hash]. */
    fun generate(): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * A key that is unknown, disabled or expired. Like an invalid token, the request continues as anonymous and a
 * denial answers `401 Token Invalid`.
 */
class ApiKeyInvalidException(message: String) : TokenVerificationException(message)

/**
 * Resolves the principal from an API key in [header] when the request carries one, and otherwise defers to
 * [delegate] (usually the JWT parser). A request that carries a key is judged by the key alone.
 */
class ApiKeySecurityContextParser(
    private val delegate: SecurityContextParser,
    private val store: ApiKeyStore,
    private val header: String = DEFAULT_HEADER,
    private val clock: Clock = Clock.systemUTC()
) : SecurityContextParser {
    companion object {
        const val DEFAULT_HEADER = "X-API-Key"
    }

    override fun parse(request: Request): SecurityContext {
        val key = request.getHeader(header)
        if (key.isBlank()) {
            return delegate.parse(request)
        }
        val apiKey = store.findByHash(ApiKeys.hash(key)) ?: throw ApiKeyInvalidException("Unknown API key.")
        if (!apiKey.enabled) {
            throw ApiKeyInvalidException("API key [${apiKey.id}] is disabled.")
        }
        apiKey.expiresAt?.let { expiresAt ->
            if (!clock.instant().isBefore(expiresAt)) {
                throw ApiKeyInvalidException("API key [${apiKey.id}] expired at [$expiresAt].")
            }
        }
        return SimpleSecurityContext(apiKey.toPrincipal())
    }
}
