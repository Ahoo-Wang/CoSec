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

package me.ahoo.cosec.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.CoSec
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Records in Redis that the CoSec data under a key prefix is in the 7.0 (CoCache 6) layout: `{p}:meta:schema`, where
 * `{p}` is the key prefix without a trailing `:`, holds [SCHEMA_VERSION].
 *
 * Unmigrated 6.x data is not always found where 7.0 reads: with a key prefix ending in `:` 6.x stored policies under
 * `myapp::policy:` while 7.0 reads `myapp:policy:`, a changed key prefix leaves the 6.x data under the old one, and a
 * rollback of such a migration deletes the 7.0 copies. 7.0 would read every policy, global `DENY` and revocation as
 * missing, which ForeignFormatMode.FAIL cannot catch since there is nothing foreign to read. [verify] therefore runs
 * before any cache is used: without the marker, any CoSec 6.x data (under [legacyKeyPrefix], untrimmed, or under
 * the current prefix) stops startup until [CoSecCoCache5Migration.migrate], which writes the marker last, has run.
 * Without the marker and without such data, the deployment is new and [verify] writes the marker.
 *
 * The check cannot find 6.x data under a prefix it is not told about: when the key prefix changes, set
 * [legacyKeyPrefix] to the 6.x one.
 *
 * @param keyPrefix `cosec.authorization.cache.key-prefix` of 7.0
 * @param legacyKeyPrefix the key prefix of the 6.x deployment, as configured (not trimmed)
 */
class CoSecSchemaMarker(
    private val redisTemplate: StringRedisTemplate,
    keyPrefix: String = CoSec.COSEC,
    legacyKeyPrefix: String = keyPrefix,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        const val SCHEMA_VERSION = "7"
        private const val SCAN_COUNT = 1000L
        private val GLOB_SPECIAL = Regex("""[*?\[\]\\]""")

        /** Where 6.x kept its data under a key prefix, and where 7.0 keeps it. */
        private val DATA_SUFFIXES = listOf(
            ":policy:",
            ":global:policy",
            ":app:permission:",
            ":app:role:permission:",
            ":role:permission:",
            ":token:revoked:",
        )
    }

    private val currentKeyPrefix = keyPrefix.trimEnd(':')

    /** The marker key, `{p}:meta:schema`. */
    val key: String = "$currentKeyPrefix:meta:schema"

    private val keyPrefixes = listOf(legacyKeyPrefix, currentKeyPrefix).distinct()
    private val dataPrefixes: List<String> = keyPrefixes.flatMap { prefix -> DATA_SUFFIXES.map { prefix + it } }

    /** One scan per root: the roots usually overlap (`myapp:` covers `myapp::`), and SCAN reads the whole keyspace. */
    private val scanRoots: List<String> = keyPrefixes.map { "$it:" }
        .sortedBy { it.length }
        .fold(mutableListOf()) { kept, root ->
            if (kept.none { root.startsWith(it) }) {
                kept += root
            }
            kept
        }

    /**
     * `true` when the marker is present.
     *
     * @throws CoSecSchemaException when the marker holds another schema version
     */
    fun isMarked(): Boolean {
        val version = redisTemplate.opsForValue().get(key) ?: return false
        if (version != SCHEMA_VERSION) {
            throw CoSecSchemaException(
                "Redis key [$key] records CoSec data schema [$version], but this CoSec reads schema " +
                    "[$SCHEMA_VERSION]. Refusing to read data in a layout it does not know."
            )
        }
        return true
    }

    /** Records that the data is in the 7.0 layout. */
    fun mark() {
        redisTemplate.opsForValue().set(key, SCHEMA_VERSION)
    }

    /** Removes the marker, so 7.0 refuses to start on what a rollback restores. */
    fun unmark() {
        redisTemplate.delete(key)
    }

    /** A key holding CoSec data under the legacy or the current prefix, or `null` when there is none. */
    fun findDataKey(): String? {
        scanRoots.forEach { root ->
            val options = ScanOptions.scanOptions()
                .match(root.replace(GLOB_SPECIAL) { "\\" + it.value } + "*")
                .count(SCAN_COUNT)
                .build()
            val found = redisTemplate.scan(options).use { cursor ->
                cursor.asSequence().firstOrNull { key -> dataPrefixes.any { key.startsWith(it) } }
            }
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Checks the data before CoSec reads it: passes when the marker is present, writes it when there is no CoSec data
     * at all (a new deployment), and fails otherwise.
     *
     * @throws CoSecSchemaException when CoSec data exists without the marker
     */
    fun verify() {
        if (isMarked()) {
            return
        }
        val dataKey = findDataKey()
        if (dataKey != null) {
            throw CoSecSchemaException(
                "Redis key [$key] is missing but CoSec data exists (for example [$dataKey]): the data was written by " +
                    "CoSec 6.x, or rolled back to it, and 7.0 would read it as missing. Stop every writer and run " +
                    "CoSecCoCache5Migration.migrate(), which writes the marker, before starting 7.0. If the key " +
                    "prefix changed, set cosec.authorization.cache.migration.legacy-key-prefix to the 6.x one."
            )
        }
        log.info { "No CoSec data found: marking [$key] as CoSec data schema [$SCHEMA_VERSION]." }
        mark()
    }
}

/** The CoSec data in Redis is not in a layout this CoSec can read safely. */
class CoSecSchemaException(message: String) : IllegalStateException(message)
