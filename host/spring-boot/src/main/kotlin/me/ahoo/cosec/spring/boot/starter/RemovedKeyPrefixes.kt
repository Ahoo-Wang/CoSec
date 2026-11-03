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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.api.CoSec
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment

/**
 * The three key prefixes `cosec.key-prefix` replaced (RC1-46, `SNP-9`, review M3 of #1200). An environment that still
 * sets one fails startup, whatever `cosec.unknown-properties` says: it was written for the old layout, whose token
 * revocations and API keys may lie under another prefix than the one now read. A revocation left there is no longer
 * read, so the token it revoked would be accepted again; renaming the property is not enough. The message says which
 * families move, from where to where, computed from the old values and the current `cosec.key-prefix`, and that the
 * revocations and API keys must be copied first.
 *
 * Every non-default layout set one of the three, so this needs no Redis scan.
 */
internal object RemovedKeyPrefixes {
    const val SNAPSHOT = "cosec.authorization.snapshot.key-prefix"
    const val CACHE = "cosec.authorization.cache.key-prefix"
    const val API_KEY = "cosec.api-key.key-prefix"
    const val KEY_PREFIX = "cosec.key-prefix"
    const val DOCUMENTATION = "https://cosec.ahoo.me/operations/upgrading#from-8-0-0-alpha-2"
    private const val API_KEYS = "api-key"

    /** What the old API key property defaulted to. */
    private const val OLD_API_KEY_PREFIX = "cosec:api-key"

    /** Throws [IllegalStateException] when [environment] sets one of the removed prefixes. */
    fun check(environment: ConfigurableEnvironment) {
        problem(environment)?.let { error(it) }
    }

    /** Why startup fails, or null when [environment] sets none of the removed prefixes. */
    fun problem(environment: ConfigurableEnvironment): String? {
        val binder = Binder.get(environment)
        fun read(name: String): String? = binder.bind(name, String::class.java).orElse(null)
        val set = listOf(SNAPSHOT, CACHE, API_KEY).filter { read(it) != null }
        if (set.isEmpty()) {
            return null
        }
        val now = (read(KEY_PREFIX) ?: CoSec.COSEC).trimEnd(':')
        val snapshot = (read(SNAPSHOT) ?: CoSec.COSEC).trimEnd(':')
        val cache = (read(CACHE) ?: CoSec.COSEC).trimEnd(':')
        val apiKeys = read(API_KEY) ?: OLD_API_KEY_PREFIX
        val moves = buildList {
            if (snapshot != now) {
                add("snapshot documents, control-plane records, candidates and shadow reports: $snapshot: -> $now:")
            }
            if (cache != now) {
                add("token revocations and memberships: $cache: -> $now:")
            }
            if (apiKeys != "$now:$API_KEYS") {
                add("API keys: $apiKeys -> $now:$API_KEYS")
            }
            if (now != CoSec.COSEC) {
                add("rate limit counters and the social login's OAuth states: ${CoSec.COSEC}: -> $now:")
            }
        }
        val properties = set.joinToString(", ")
        val where = if (moves.isEmpty()) {
            "With the old values and $KEY_PREFIX ($now), no family moves.\n"
        } else {
            "With the old values and $KEY_PREFIX ($now), these families move:\n" + moves.joinToString("") { "  $it\n" } +
                "Copy the token revocations and the API keys under the new prefix before starting this version (COPY, " +
                "or RENAME, which keeps their expiry): a token revoked under the old prefix would be accepted again, " +
                "and the API keys refused. Memberships, counters and OAuth states fill again on their own. Set " +
                "$KEY_PREFIX to the snapshot documents' prefix.\n"
        }
        return "CoSec no longer reads $properties: every key a deployment writes lives under $KEY_PREFIX. " + where +
            "Then remove $properties; ${UnknownProperties.MODE_KEY} does not turn this check off. See $DOCUMENTATION"
    }
}
