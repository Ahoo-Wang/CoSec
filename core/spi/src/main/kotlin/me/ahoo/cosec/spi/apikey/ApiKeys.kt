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

package me.ahoo.cosec.spi.apikey

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * A credential of a principal (RFC 0009 §4): whoever presents the key acts as [principalId] in [tenantId], with the
 * memberships the `MembershipProvider` gives that principal, narrowed to [roles]. A key grants nothing of its own: a key
 * of a user is a personal access token, one of a workload a service account's key, and neither is ever more than its
 * principal (`IDN-1`). The principal must be known to the provider and active, or the key is refused (`IDN-3`).
 *
 * Only the key's hash is ever stored (see [ApiKeys.hash]); the key itself is shown once, when it is created. A plain
 * class, not a data class, so that a field can be added later without breaking callers (RFC 0010 §1).
 *
 * @param roles the cap, as `role` or `role@space`: null does not narrow, an empty set leaves no roles (`IDN-2`)
 * @param expiresAt when the key stops working; null never expires
 */
class ApiKey(
    val id: String,
    val principalId: String,
    val tenantId: String,
    val roles: Set<String>? = null,
    val expiresAt: Instant? = null,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotEmpty()) { "An API key has an id." }
        require(principalId.isNotEmpty()) { "API key [$id] has a principal." }
        require(tenantId.isNotEmpty()) { "API key [$id] belongs to a tenant." }
    }

    /** Whether the key works at [now]: enabled, and not past [expiresAt]. */
    fun usableAt(now: Instant): Boolean = enabled && (expiresAt == null || now.isBefore(expiresAt))

    override fun toString(): String =
        "ApiKey(id=$id, principalId=$principalId, tenantId=$tenantId, roles=$roles, expiresAt=$expiresAt, enabled=$enabled)"

    companion object {
        /** Principal attribute naming the key a request authenticated with. */
        const val API_KEY_ID_ATTRIBUTE = "apiKeyId"
    }
}

/**
 * Looks up API keys by hash, synchronously: the store keys are provisioned into (`InMemoryApiKeyStore`, and
 * `RedisApiKeyStore` in `cosec-cocache`). Requests never read it: the pipeline reads keys through [ApiKeyReader].
 */
fun interface ApiKeyStore {
    fun findByHash(hash: String): ApiKey?
}

/**
 * The read side of an API key store for the coroutine pipeline (RFC 0006 §5.3.2): never blocks the calling thread, which
 * may be an event loop. A lookup that fails throws: the request is answered 503.
 */
fun interface ApiKeyReader {
    suspend fun findByHash(hash: String): ApiKey?
}

/**
 * @param keys API keys by hash
 */
class InMemoryApiKeyStore(private val keys: Map<String, ApiKey>) : ApiKeyStore {
    override fun findByHash(hash: String): ApiKey? = keys[hash]

    /** The same keys for the pipeline: a map lookup, which never blocks. */
    val reader: ApiKeyReader = ApiKeyReader { keys[it] }
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
