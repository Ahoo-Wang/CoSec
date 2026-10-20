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
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.redis.migration.CoCache5Format
import me.ahoo.cocache.redis.migration.CoCache5Migration
import me.ahoo.cocache.redis.migration.CoCache5MigrationReport
import me.ahoo.cocache.redis.migration.CoCache5Migrator
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Converts what CoSec 6.x stored with CoCache 5 into the CoCache 6 format of 7.0, with CoCache's [CoCache5Migrator].
 *
 * CoCache 6 cannot read CoCache 5 values, and policies, the global policy index and app permissions have no other copy
 * than Redis. A 6.x value 7.0 reads fails the read (the caches use `ForeignFormatMode.FAIL`), so requests are denied
 * with `500`. 6.x data 7.0 does not read at all, because it sits under other keys (a key prefix ending in `:`, a
 * changed key prefix), would read as missing instead: [CoSecSchemaMarker] stops 7.0 from starting until [migrate] has
 * written its marker.
 *
 * | Data | 6.x key | 6.x format |
 * |---|---|---|
 * | Policies | `{p}:policy:{policyId}` | JSON string |
 * | Global policy index | `{p}:global:policy` | native Redis set of policy ids |
 * | App permissions | `{p}:app:permission:{appId}` | JSON string |
 * | Revoked tokens | `{p}:token:revoked:{tokenId}` | JSON `true`, with the token's remaining lifetime |
 *
 * `{p}` is [legacyKeyPrefix] as configured, exactly as 6.x used it: 6.x did not trim a trailing `:`, so a key prefix
 * of `myapp:` stored policies under `myapp::policy:`. Cached misses (the `_nil_` sentinel) become CoCache 6 cached
 * misses. Role grants are not part of this migration: they move to app-scoped keys with [RolePermissionKeyMigration].
 * Policy history (`{p}:policy:history:*`) is not CoCache data and is left alone, which is why policies are listed
 * rather than scanned; a policy whose id merely starts with `history`, such as `history-viewer`, is migrated.
 *
 * When the 6.x and 7.0 keys are equal (the same key prefix, without a trailing `:`), the migration is in place: every
 * original value is first backed up, and [rollback] restores them. 7.0 trims a trailing `:`
 * ([CoSecCacheSpecs.cacheNamespace]: `myapp:policy:`), so with such a prefix, or with another [keyPrefix], the values
 * are copied to the new keys instead, and the 6.x keys stay unchanged.
 *
 * The values are written with the specs given here, which do not go through the application's `CacheSpecCustomizer`s:
 * a customizer that changes a cache's codec makes the migrated values unreadable to that cache (its reads fail).
 *
 * Stop every writer (6.x and 7.0 instances) first: run [dryRun], then [migrate], verify, start 7.0, and [dropBackups]
 * once 7.0 runs well. Every operation can be repeated: migrated keys are skipped. The global index is migrated last.
 *
 * @param migrator opens a migrator for one operation; it is closed afterwards
 * @param legacyKeyPrefix the 6.x key prefix, not trimmed: 6.x appended `:policy:` and the like to it as it was
 * @param keyPrefix the 7.0 key prefix the specs were built with; the schema marker is written under it
 */
class CoSecCoCache5Migration(
    private val migrator: () -> CoCache5Migrator,
    private val redisTemplate: StringRedisTemplate,
    private val policySpec: CacheSpec<String, Policy>,
    private val globalPolicyIndexSpec: SetCacheSpec<String, String>,
    private val appPermissionSpec: CacheSpec<AppId, AppPermission>,
    private val revokedTokenSpec: CacheSpec<String, Boolean>? = null,
    private val legacyKeyPrefix: String = CoSec.COSEC,
    keyPrefix: String = legacyKeyPrefix,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val SCAN_COUNT = 1000L
        private const val LISTED_KEYS = 20
        private val GLOB_SPECIAL = Regex("""[*?\[\]\\]""")
    }

    private val policyKeyPrefix = "$legacyKeyPrefix:policy:"
    private val policyHistoryKeyPrefix = "$legacyKeyPrefix:policy:history:"
    private val currentKeyPrefix = keyPrefix.trimEnd(':')

    /** The marker [migrate] writes and [rollback] removes. */
    val schemaMarker: CoSecSchemaMarker = CoSecSchemaMarker(redisTemplate, keyPrefix, legacyKeyPrefix)

    init {
        require(policySpec.namespace == CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.POLICY_CACHE_NAME)) {
            "keyPrefix [$keyPrefix] does not match the namespace [${policySpec.namespace}] of the policy spec."
        }
    }

    /**
     * Reads and decodes every 6.x value without writing anything. Run it with every writer stopped, right before
     * [migrate]: a dry run while 6.x still writes reports values that may have changed by the time [migrate] runs.
     */
    fun dryRun(): CoCache5MigrationReport = withMigrator { it.dryRun(migrations()) }

    /**
     * Migrates every 6.x value, backing up the original, then writes the [schemaMarker] that lets 7.0 start. Aborts
     * on the first value it cannot decode (`CoCache5MigrationException`, carrying the report so far), and on a key
     * that changed while it was migrated, such as a revoked token that just expired; fix the cause and run it again.
     */
    fun migrate(): CoCache5MigrationReport = withMigrator { it.migrate(migrations()) }.also {
        schemaMarker.mark()
    }

    /**
     * The 7.0 keys a [rollback] cannot give back to 6.x: policies, app permissions, revoked tokens and the global
     * index that have no backup (in place) or no 6.x key (new keys), that is, keys 7.0 created after the migration
     * or wrote before it. Role grants are not listed: 7.0 keeps them under new keys, which 6.x never reads.
     */
    fun keysWithoutBackup(): List<String> = withMigrator { keysWithoutBackup(it.options.backupPrefix) }

    /**
     * Restores the 6.x values from their backups, with every writer stopped, and removes the [schemaMarker] first, so
     * a 7.0 instance started on the restored data refuses to start. It restores the snapshot taken by [migrate], so
     * what 7.0 wrote since is lost:
     *
     * - changes 7.0 made to migrated keys (policies, app permissions, the global index) are overwritten by the backup;
     * - a policy 7.0 added to the global index is dropped from it when the index is restored;
     * - keys 7.0 created ([keysWithoutBackup]), such as new policies, app permissions or revocations, have no 6.x
     *   value. 6.x does not fail on them: CoCache 5 reads a value it cannot decode as missing and overwrites it with a
     *   cached miss, so a deny policy created by 7.0 stops applying and a token revoked by 7.0 is accepted again.
     *   Write those policies and revoke those tokens again through 6.x before it serves traffic.
     *
     * When the 6.x key prefix ended with `:`, the migration wrote to new keys: those are deleted and the 6.x keys,
     * never changed, are used again.
     *
     * @param allowKeysWithoutBackup roll back although [keysWithoutBackup] is not empty; without it such a rollback
     * fails with [KeysWithoutBackupException] and changes nothing
     */
    @JvmOverloads
    fun rollback(allowKeysWithoutBackup: Boolean = false): CoCache5MigrationReport = withMigrator { migrator ->
        val unbacked = keysWithoutBackup(migrator.options.backupPrefix)
        if (unbacked.isNotEmpty()) {
            if (!allowKeysWithoutBackup) {
                throw KeysWithoutBackupException(unbacked)
            }
            log.warn {
                "Rolling back although ${unbacked.size} keys have no 6.x value: ${unbacked.take(LISTED_KEYS)}. " +
                    "Write them again through 6.x before it serves traffic."
            }
        }
        schemaMarker.unmark()
        migrator.rollback(migrations())
    }

    /** Deletes the backups once 7.0 runs well; [rollback] is no longer possible afterwards. */
    fun dropBackups(): CoCache5MigrationReport = withMigrator { it.dropBackups(migrations()) }

    /** The migrations, in order: policies, app permissions, revoked tokens, then the global policy index. */
    fun migrations(): List<CoCache5Migration> = buildList {
        policyMigration()?.let { add(it) }
        add(
            CoCache5Migration.scalar(appPermissionSpec, CoCache5Format.string())
                .sourcePrefix("$legacyKeyPrefix:app:permission:")
                .build()
        )
        revokedTokenSpec?.let {
            add(
                CoCache5Migration.scalar(it, CoCache5Format.string())
                    .sourcePrefix("$legacyKeyPrefix:token:revoked:")
                    .build()
            )
        }
        add(
            CoCache5Migration.set(globalPolicyIndexSpec)
                .sourceKey("$legacyKeyPrefix:global:policy", CoSecCacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY)
                .build()
        )
    }

    /** Policies listed one by one: a scan of the policy prefix would also hit the policy history. */
    private fun policyMigration(): CoCache5Migration? {
        val keys = policyKeys()
        if (keys.isEmpty()) {
            return null
        }
        val builder = CoCache5Migration.scalar(policySpec, CoCache5Format.string())
        keys.forEach { builder.sourceKey(it, it.removePrefix(policyKeyPrefix)) }
        return builder.build()
    }

    private fun policyKeys(): List<String> {
        val options = ScanOptions.scanOptions()
            .match(glob(policyKeyPrefix) + "*")
            .type(DataType.STRING)
            .count(SCAN_COUNT)
            .build()
        return redisTemplate.scan(options).use { cursor ->
            cursor.asSequence().filterNot { it.startsWith(policyHistoryKeyPrefix) }.distinct().sorted().toList()
        }
    }

    private fun keysWithoutBackup(backupPrefix: String): List<String> {
        val indexKey = "${globalPolicyIndexSpec.namespace}:${CoSecCacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY}"
        val policyHistory = "${policySpec.namespace}:history:"
        val prefixes = listOfNotNull(policySpec, appPermissionSpec, revokedTokenSpec).map { "${it.namespace}:" }
        val keys = prefixes.flatMap { prefix -> scan(prefix).filterNot { it.startsWith(policyHistory) } } +
            listOf(indexKey).filter { redisTemplate.hasKey(it) }
        return keys.distinct().sorted().filterNot { key ->
            // 7.0 keys are `{trimmed prefix}:{namespace}:{key}`, 6.x keys `{legacy prefix}:{namespace}:{key}`
            val legacyKey = legacyKeyPrefix + key.removePrefix(currentKeyPrefix)
            if (legacyKey == key) redisTemplate.hasKey(backupPrefix + key) else redisTemplate.hasKey(legacyKey)
        }
    }

    private fun scan(prefix: String): List<String> {
        val options = ScanOptions.scanOptions().match(glob(prefix) + "*").count(SCAN_COUNT).build()
        return redisTemplate.scan(options).use { cursor -> cursor.asSequence().toList() }
    }

    private fun glob(prefix: String): String = prefix.replace(GLOB_SPECIAL) { "\\" + it.value }

    private fun <T> withMigrator(block: (CoCache5Migrator) -> T): T = migrator().use(block)
}

/**
 * A [CoSecCoCache5Migration.rollback] would leave [keys] without a 6.x value; nothing was changed.
 */
class KeysWithoutBackupException(val keys: List<String>) : IllegalStateException(
    "${keys.size} keys have no 6.x value to roll back to, for example ${keys.take(20)}: 7.0 created or rewrote them, " +
        "and 6.x would read them as missing (a deny policy stops applying, a revoked token is accepted again). " +
        "Call rollback(allowKeysWithoutBackup = true), then write these policies and revoke these tokens again " +
        "through 6.x before it serves traffic."
)
