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
package me.ahoo.cosec.spring.boot.starter.apikey

import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Instant

/**
 * API keys for machine clients. Off by default. The header they come in is `cosec.pipeline.api-key-header`.
 */
@ConfigurationProperties(ApiKeyProperties.PREFIX)
data class ApiKeyProperties(
    @DefaultValue("false") override var enabled: Boolean = false,
    /**
     * Where keys live: `memory` (the [keys] below) or `redis` (shared by every instance, needs `cosec-cocache`).
     */
    @DefaultValue("memory") var store: Store = Store.MEMORY,
    /**
     * Redis key prefix, when [store] is `redis`.
     */
    @DefaultValue(DEFAULT_KEY_PREFIX) var keyPrefix: String = DEFAULT_KEY_PREFIX,
    /**
     * Keys of the in-memory store, by their SHA-256 hash. Ignored with the Redis store.
     */
    var keys: List<Key> = emptyList(),
) : EnabledCapable {
    companion object {
        const val PREFIX: String = "cosec.api-key"
        const val ENABLED_KEY: String = PREFIX + ENABLED_SUFFIX_KEY
        const val STORE_KEY: String = "$PREFIX.store"
        const val DEFAULT_KEY_PREFIX = "cosec:api-key"
    }

    enum class Store {
        MEMORY,
        REDIS,
    }

    data class Key(
        var id: String = "",
        /** SHA-256 of the key, hex-encoded. Never configure the key itself. */
        var hash: String = "",
        var principalId: String = "",
        var tenantId: String? = null,
        var roles: Set<String> = emptySet(),
        var policies: Set<String> = emptySet(),
        var attributes: Map<String, String> = emptyMap(),
        var expiresAt: Instant? = null,
        @DefaultValue("true") var enabled: Boolean = true,
    ) {
        fun toApiKey(): ApiKey {
            require(id.isNotBlank()) { "An API key needs an id." }
            require(HASH.matches(hash)) { "API key [$id] needs the SHA-256 hex of the key as hash." }
            require(principalId.isNotBlank()) { "API key [$id] needs a principal-id." }
            return ApiKey(id, principalId, tenantId, roles, policies, attributes, expiresAt, enabled)
        }

        private companion object {
            val HASH = Regex("[0-9a-f]{64}")
        }
    }
}
