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
 * API keys: credentials of principals, for machine clients. Off by default. The header they come in is
 * `cosec.pipeline.api-key-header`. A key's memberships come from its principal's `MembershipProvider`, so one is
 * required (RFC 0009 §4.2, `IDN-6`).
 *
 * Unknown fields fail binding rather than being dropped: a key configured with 8.0's predecessors' `policies` or
 * `attributes`, which keys no longer carry, stops startup instead of silently losing or keeping access.
 */
@ConfigurationProperties(ApiKeyProperties.PREFIX, ignoreUnknownFields = false)
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
        /** The principal's tenant: required. */
        var tenantId: String = "",
        /**
         * The cap, as `role` or `role@space`: the key's memberships are its principal's, narrowed to these; empty, it
         * has no roles. Set it, or [uncapped]: never both, never neither.
         */
        var roles: Set<String>? = null,
        /**
         * True for a key that holds everything its principal holds. Said explicitly, so that a key whose `roles` were
         * left out by mistake never gets that silently.
         */
        var uncapped: Boolean = false,
        var expiresAt: Instant? = null,
        @DefaultValue("true") var enabled: Boolean = true,
    ) {
        fun toApiKey(): ApiKey {
            require(id.isNotBlank()) { "An API key needs an id." }
            require(HASH.matches(hash)) { "API key [$id] needs the SHA-256 hex of the key as hash." }
            require(principalId.isNotBlank()) { "API key [$id] needs a principal-id." }
            require(tenantId.isNotBlank()) { "API key [$id] needs a tenant-id." }
            require((roles != null) != uncapped) {
                "API key [$id] needs exactly one of roles (its cap; [] for no roles) and uncapped: true, " +
                    "not ${if (uncapped) "both" else "neither"}."
            }
            return ApiKey(id, principalId, tenantId, roles, expiresAt, enabled)
        }

        private companion object {
            val HASH = Regex("[0-9a-f]{64}")
        }
    }
}
