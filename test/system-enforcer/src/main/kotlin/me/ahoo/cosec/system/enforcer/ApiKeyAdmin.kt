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

package me.ahoo.cosec.system.enforcer

import me.ahoo.cosec.cocache.RedisApiKeyStore
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeys
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Issues and revokes API keys in the Redis store every process shares (`RedisApiKeyStore` blocks on Redis: a servlet
 * thread may). The platform document permits these routes to the platform administrator `root` only. A key without an
 * expiry cannot be forwarded by a gateway, so the scenarios always give one.
 */
@RestController
class ApiKeyAdmin(private val store: RedisApiKeyStore) {
    /** `POST /admin/api-keys`: the key (shown once) and its hash, which revokes it. */
    @PostMapping("/admin/api-keys")
    fun issue(@RequestBody body: Map<String, Any?>): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val roles = (body["roles"] as List<String>?)?.toSet()
        val apiKey = ApiKey(
            id = body.getValue("id") as String,
            principalId = body.getValue("principal") as String,
            tenantId = body.getValue("tenant") as String,
            app = body["app"] as String?,
            roles = roles,
            expiresAt = Instant.parse(body.getValue("expiresAt") as String),
        )
        val key = store.issue(apiKey)
        return mapOf("key" to key, "hash" to ApiKeys.hash(key))
    }

    @DeleteMapping("/admin/api-keys/{hash}")
    fun revoke(@PathVariable hash: String): Map<String, Boolean> = mapOf("revoked" to store.revoke(hash))
}
