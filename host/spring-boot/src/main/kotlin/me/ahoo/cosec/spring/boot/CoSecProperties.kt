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
package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

internal const val ENABLED_SUFFIX_KEY = ".enabled"

/**
 * CoSec Properties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CoSecProperties.PREFIX)
@InternalCoSecApi
class CoSecProperties(
    /** Whether CoSec's auto-configurations are on at all; the unknown-key check and a gateway's identity strip hold anyway. */
    @DefaultValue("true") override var enabled: Boolean = true,
    /**
     * The prefix of every Redis key this deployment writes (`SNP-9`, RC1-46): snapshot documents, control-plane
     * records, shadow reports, token revocations, memberships (`<p>:snapshot:…`, `<p>:control:…`, `<p>:token:…`, …),
     * API keys (`<p>:api-key:…`) and rate limit counters (`<p>:rate-limiter:…`). Two deployments on one Redis that differ
     * in it share nothing. Letters, digits, `_`, `.` and `-` only, or such a string wrapped whole in braces, a Redis
     * Cluster hash tag (`{cosec}`); never `:`, so that no deployment's prefix nests inside another's (`a` and `a:b` would
     * share keys): anything else fails startup.
     */
    // The stores' own default: one definition, so the property's default and the keys the stores write agree. A const,
    // inlined here, so `cosec-cocache` stays an optional dependency.
    @DefaultValue(DEFAULT_KEY_PREFIX) var keyPrefix: String = DEFAULT_KEY_PREFIX,
) : EnabledCapable {
    init {
        requireKeyPrefix(keyPrefix)
    }

    /** The prefix of one family of this deployment's keys: `<keyPrefix>:<family>`. */
    fun keysOf(family: String): String = "$keyPrefix:$family"

    @InternalCoSecApi
    companion object {
        /** The prefix of every `cosec.*` property. */
        const val PREFIX = "cosec"

        const val KEY_PREFIX_KEY = "$PREFIX.key-prefix"

        private val KEY_PREFIX = Regex("[A-Za-z0-9_.-]+|\\{[A-Za-z0-9_.-]+}")

        /**
         * Refuses a key prefix that could nest inside another deployment's (`SNP-9`, DR1-D-4), naming what it holds:
         * a trailing `:`, which 8.0's previews tolerated, is a `:` to remove; one inside names other keys to migrate.
         */
        fun requireKeyPrefix(prefix: String) {
            if (KEY_PREFIX.matches(prefix)) return
            val wrong = prefix.filterNot { it.isLetterOrDigit() && it.code < ASCII || it in "_.-" }.toSet()
                .joinToString(", ") { "'$it'" }
            val hint = when {
                prefix.isEmpty() -> "It is empty."
                '{' in prefix || '}' in prefix ->
                    "Braces are a hash tag only when one pair wraps the whole prefix ({cosec}), never part of it."
                prefix.trimEnd(':').let { KEY_PREFIX.matches(it) } ->
                    "Remove the trailing ':' (${prefix.trimEnd(':')}): the keys are the same."
                ':' in prefix -> "A ':' inside it names keys under another prefix: move them to one without it " +
                    "(https://cosec.ahoo.me/operations/upgrading-previews)."
                else -> "Write it with letters, digits, '_', '.' and '-'."
            }
            throw IllegalArgumentException(
                "$KEY_PREFIX_KEY [$prefix] holds $wrong: a key prefix is letters, digits, '_', '.' and '-' (cosec), or " +
                    "such a string wrapped whole in braces, a Redis Cluster hash tag ({cosec}), so that no deployment's " +
                    "prefix nests inside another's (SNP-9). $hint"
            )
        }

        private const val ASCII = 128
    }
}
