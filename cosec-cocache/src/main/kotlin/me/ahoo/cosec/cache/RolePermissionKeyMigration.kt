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
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.SpacedRoleId
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * One-off migration of 6.x (CoCache 5) role grants (`<legacy prefix><roleId>[@<spaceId>]`, no app) to the app-scoped keys of
 * [RolePermissionCache] ([AppSpacedRoleId]).
 *
 * Legacy grants do not say which app they were meant for, so the operator names the target app on every run;
 * nothing is guessed and nothing is copied to every app. Authorization never reads legacy keys: a role's grants are
 * missing (denied) in an app until they are migrated to it.
 *
 * Writes go through [rolePermissionCache], so other instances drop their local copies. A run is idempotent: a target
 * that already holds the same grants is left alone, and one that holds different grants is reported as a conflict
 * and kept unless [migrate] is told to overwrite. Legacy keys stay until [deleteLegacy] removes them, so a rollback to
 * 6.x finds them unchanged.
 *
 * Legacy grants are CoCache 5 native Redis sets and are read directly; targets are CoCache 6 values and are read and
 * written through [rolePermissionCache] (a miss on a cache without a loader writes nothing to Redis). This is the only
 * migration of role grants: CoCache's 5.x migrator must not be pointed at the legacy prefix.
 *
 * @param legacyKeyPrefix the 6.x key prefix, `<cosec.authorization.cache.key-prefix>:role:permission:` by default
 * @param missingGuardSentinel the value CoCache stores for a cached miss; such keys hold no grant
 */
class RolePermissionKeyMigration(
    private val redisTemplate: StringRedisTemplate,
    private val rolePermissionCache: RolePermissionCache,
    private val legacyKeyPrefix: String,
    private val missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL
) {
    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_MISSING_GUARD_SENTINEL = "_nil_"
        private const val SCAN_COUNT = 1000L
        private val GLOB_SPECIAL = Regex("""[*?\[\]\\]""")
    }

    /**
     * Copies legacy grants to [appId].
     *
     * @param appId the app the legacy grants are meant for
     * @param roleIds when set, only these (bare) role ids are migrated, so grants of several apps that share the
     * legacy key space can be split between apps
     * @param dryRun report what would change without writing
     * @param overwrite replace targets that already hold different grants (e.g. to re-sync grants changed by 6.x
     * instances during a rolling upgrade)
     */
    fun migrate(
        appId: AppId,
        roleIds: Set<RoleId>? = null,
        dryRun: Boolean = false,
        overwrite: Boolean = false
    ): RolePermissionMigrationReport {
        require(AppSpacedRoleId.isValidAppId(appId)) {
            "App id [$appId] must be non-empty and must not contain '${AppSpacedRoleId.APP_ID_SEPARATOR}'."
        }
        val migrated = mutableListOf<RolePermissionMigration>()
        val unchanged = mutableListOf<RolePermissionMigration>()
        val conflicts = mutableListOf<RolePermissionMigration>()
        val skipped = mutableListOf<SkippedLegacyKey>()
        forEachLegacyKey { legacyKey ->
            val permissions = when (val read = readLegacy(legacyKey)) {
                is LegacyRead.Skip -> {
                    skipped += SkippedLegacyKey(legacyKey, read.reason)
                    return@forEachLegacyKey
                }
                is LegacyRead.Grants -> read.permissions
            }
            val target = when (val parsed = parseLegacyKey(appId, legacyKey)) {
                null -> {
                    skipped += SkippedLegacyKey(legacyKey, "ambiguous or empty role or space id")
                    return@forEachLegacyKey
                }
                else -> parsed
            }
            if (roleIds != null && target.roleId !in roleIds) {
                return@forEachLegacyKey
            }
            val migration = RolePermissionMigration(legacyKey, target, permissions)
            val existing = rolePermissionCache[target]
            when {
                existing == permissions -> unchanged += migration
                existing != null && !overwrite -> conflicts += migration
                else -> {
                    if (!dryRun) {
                        rolePermissionCache.put(target, permissions)
                    }
                    migrated += migration
                }
            }
        }
        val report = RolePermissionMigrationReport(appId, dryRun, migrated, unchanged, conflicts, skipped)
        log.info {
            "Role permission migration to app [$appId] (dryRun=$dryRun): ${migrated.size} migrated, " +
                "${unchanged.size} unchanged, ${conflicts.size} conflicts, ${skipped.size} skipped."
        }
        return report
    }

    /**
     * Deletes every legacy key. Run it once every app's grants are migrated and verified; until then the legacy keys
     * are harmless, since authorization never reads them.
     *
     * @return the deleted (or, with [dryRun], the matching) keys
     */
    fun deleteLegacy(dryRun: Boolean = false): List<String> {
        val keys = mutableListOf<String>()
        forEachLegacyKey { keys += it }
        if (!dryRun && keys.isNotEmpty()) {
            keys.chunked(SCAN_COUNT.toInt()).forEach { redisTemplate.delete(it) }
        }
        log.info { "Deleted legacy role permission keys (dryRun=$dryRun): ${keys.size}." }
        return keys
    }

    private inline fun forEachLegacyKey(action: (String) -> Unit) {
        val pattern = legacyKeyPrefix.replace(GLOB_SPECIAL) { "\\" + it.value } + "*"
        val options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build()
        // SCAN may return a key more than once.
        val seen = HashSet<String>()
        redisTemplate.scan(options).use { cursor ->
            cursor.forEach { key ->
                if (seen.add(key)) {
                    action(key)
                }
            }
        }
    }

    private sealed interface LegacyRead {
        data class Grants(val permissions: Set<PermissionId>) : LegacyRead
        data class Skip(val reason: String) : LegacyRead
    }

    private fun readLegacy(legacyKey: String): LegacyRead {
        if (redisTemplate.type(legacyKey) != DataType.SET) {
            return LegacyRead.Skip("not a set")
        }
        val permissions = readGrants(legacyKey) ?: return LegacyRead.Skip("no grants (cached miss)")
        return LegacyRead.Grants(permissions)
    }

    /** The grants stored at [key], or `null` when it is absent or holds a cached miss. */
    private fun readGrants(key: String): Set<PermissionId>? {
        val permissions = redisTemplate.opsForSet().members(key).orEmpty()
        if (permissions.isEmpty() || permissions == setOf(missingGuardSentinel)) {
            return null
        }
        return permissions
    }

    /**
     * Parses `<roleId>[@<spaceId>]`. A legacy key with more than one `@` is skipped: 6.x wrote it from a role or space
     * id containing `@`, and which split was meant cannot be told.
     */
    private fun parseLegacyKey(appId: AppId, legacyKey: String): AppSpacedRoleId? {
        val suffix = legacyKey.removePrefix(legacyKeyPrefix)
        val parts = suffix.split(SpacedRoleId.SPACE_ID_SEPARATOR)
        val roleId = parts[0]
        val spaceId = when (parts.size) {
            1 -> SpaceIdCapable.DEFAULT
            2 -> parts[1].ifEmpty { return null }
            else -> return null
        }
        if (!AppSpacedRoleId.isValidRoleId(roleId)) {
            return null
        }
        return AppSpacedRoleId(appId, roleId, spaceId)
    }
}

/** One legacy grant and the app-scoped key it migrates to. */
data class RolePermissionMigration(
    val legacyKey: String,
    val target: AppSpacedRoleId,
    val permissions: Set<PermissionId>
)

data class SkippedLegacyKey(val legacyKey: String, val reason: String)

/**
 * @property migrated written (or, on a dry run, to be written)
 * @property unchanged the target already holds the same grants
 * @property conflicts the target holds different grants and was kept
 * @property skipped legacy keys that hold no migratable grant
 */
data class RolePermissionMigrationReport(
    val appId: AppId,
    val dryRun: Boolean,
    val migrated: List<RolePermissionMigration>,
    val unchanged: List<RolePermissionMigration>,
    val conflicts: List<RolePermissionMigration>,
    val skipped: List<SkippedLegacyKey>
)
