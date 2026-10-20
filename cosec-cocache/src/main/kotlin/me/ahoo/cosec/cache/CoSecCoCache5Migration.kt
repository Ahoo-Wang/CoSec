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
 * than Redis. Until they are migrated, reading one fails (the caches use `ForeignFormatMode.FAIL`), so requests are
 * denied with `500`; nothing is ever read as missing.
 *
 * | Data | 6.x key | 6.x format |
 * |---|---|---|
 * | Policies | `{p}:policy:{policyId}` | JSON string |
 * | Global policy index | `{p}:global:policy` | native Redis set of policy ids |
 * | App permissions | `{p}:app:permission:{appId}` | JSON string |
 * | Revoked tokens | `{p}:token:revoked:{tokenId}` | JSON `true`, with the token's remaining lifetime |
 *
 * `{p}` is [legacyKeyPrefix]. Cached misses (the `_nil_` sentinel) become CoCache 6 cached misses. Role grants are not
 * part of this migration: they move to app-scoped keys with [RolePermissionKeyMigration]. Policy history
 * (`{p}:policy:history:*`) is not CoCache data and is left alone, which is why policies are listed rather than scanned.
 *
 * The 7.0 keys equal the 6.x keys (with the same key prefix), so the migration is in place: every original value is
 * first backed up, and [rollback] restores them. Stop every writer (6.x and 7.0 instances) first: run [dryRun], then
 * [migrate], verify, start 7.0, and [dropBackups] once 7.0 runs well. Every operation can be repeated: migrated keys are
 * skipped. The global index is migrated last.
 *
 * @param migrator opens a migrator for one operation; it is closed afterwards
 */
class CoSecCoCache5Migration(
    private val migrator: () -> CoCache5Migrator,
    private val redisTemplate: StringRedisTemplate,
    private val policySpec: CacheSpec<String, Policy>,
    private val globalPolicyIndexSpec: SetCacheSpec<String, String>,
    private val appPermissionSpec: CacheSpec<AppId, AppPermission>,
    private val revokedTokenSpec: CacheSpec<String, Boolean>? = null,
    legacyKeyPrefix: String = CoSec.COSEC,
) {
    companion object {
        private const val SCAN_COUNT = 1000L
    }

    private val legacyKeyPrefix = legacyKeyPrefix.trimEnd(':')
    private val policyKeyPrefix = "${this.legacyKeyPrefix}:policy:"
    private val policyHistoryKeyPrefix = "${this.legacyKeyPrefix}:policy:history"

    /** Reads and decodes every 6.x value without writing anything: run it before stopping writers. */
    fun dryRun(): CoCache5MigrationReport = withMigrator { it.dryRun(migrations()) }

    /**
     * Migrates every 6.x value, backing up the original. Aborts on the first value it cannot decode
     * (`CoCache5MigrationException`, carrying the report so far); fix the cause and run it again.
     */
    fun migrate(): CoCache5MigrationReport = withMigrator { it.migrate(migrations()) }

    /** Restores the 6.x values from their backups. What 7.0 wrote since the migration is lost. */
    fun rollback(): CoCache5MigrationReport = withMigrator { it.rollback(migrations()) }

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
            .match("$policyKeyPrefix*")
            .type(DataType.STRING)
            .count(SCAN_COUNT)
            .build()
        return redisTemplate.scan(options).use { cursor ->
            cursor.asSequence().filterNot { it.startsWith(policyHistoryKeyPrefix) }.distinct().sorted().toList()
        }
    }

    private fun <T> withMigrator(block: (CoCache5Migrator) -> T): T = migrator().use(block)
}
