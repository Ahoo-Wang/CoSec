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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.redis.migration.CoCache5MigrationOptions
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.policy.history.PolicyRevisions
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import me.ahoo.cosec.spring.boot.starter.authorization.AuthorizationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * CacheProperties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
class CacheProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue(CoSec.COSEC) var keyPrefix: String = CoSec.COSEC,
    @NestedConfigurationProperty
    var policy: CacheConfiguration = CacheConfiguration(),
    @NestedConfigurationProperty
    var role: CacheConfiguration = CacheConfiguration(),
    @NestedConfigurationProperty
    var token: CacheConfiguration = CacheConfiguration(expireAfterWrite = 30, maximumSize = 100_000),
    @NestedConfigurationProperty
    var history: PolicyHistoryConfiguration = PolicyHistoryConfiguration(),
    @NestedConfigurationProperty
    var migration: MigrationConfiguration = MigrationConfiguration(),
) : EnabledCapable {
    companion object {
        const val PREFIX: String = AuthorizationProperties.PREFIX + ".cache"
    }

    /**
     * The key prefix of the 6.x deployment, as configured: 6.x appended `:policy:` and the like without trimming a
     * trailing `:` (`myapp:` stored policies under `myapp::policy:`), so the migrations do not trim it either.
     */
    val legacyKeyPrefix: String
        get() = migration.legacyKeyPrefix ?: keyPrefix

    /**
     * Role grants of 6.x, keyed without the app (`<prefix><roleId>[@<spaceId>]`). Authorization never reads them;
     * only [me.ahoo.cosec.cache.RolePermissionKeyMigration] does.
     */
    val legacyRolePermissionKeyPrefix: String
        get() = "$legacyKeyPrefix:role:permission:"
    val policyHistoryKeyPrefix: String = "$keyPrefix:policy:history"
}

/**
 * Revision history of policies saved through the Redis repository.
 */
data class PolicyHistoryConfiguration(
    var enabled: Boolean = false,
    /** Revisions kept per policy; older ones are dropped. */
    var maxRevisions: Int = PolicyRevisions.DEFAULT_MAX_REVISIONS,
)

/**
 * How CoSec 6.x (CoCache 5) stored its data, for the one-off migrations to 7.0 ([me.ahoo.cosec.cache.CoSecCoCache5Migration]
 * and [me.ahoo.cosec.cache.RolePermissionKeyMigration]).
 */
data class MigrationConfiguration(
    /**
     * `cosec.authorization.cache.key-prefix` of the 6.x deployment, as it was configured (not trimmed); defaults to
     * the current key prefix.
     */
    var legacyKeyPrefix: String? = null,
    /** `cocache.redis.missing-guard-sentinel` of the 6.x deployment. */
    var missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
    /** Where the CoCache migration backs up each 6.x value it converts, for a rollback. */
    var backupPrefix: String = CoCache5MigrationOptions.DEFAULT_BACKUP_PREFIX,
) {
    companion object {
        const val DEFAULT_MISSING_GUARD_SENTINEL = CoCache5MigrationOptions.DEFAULT_MISSING_GUARD_SENTINEL
    }
}

/**
 * Local (in-process) cache settings. Unset values ([UNSET]) keep CoCache's defaults: 10,000 entries and a local copy
 * that lives at most 5 minutes (`localMaxAge`).
 */
data class CacheConfiguration(
    var initialCapacity: Int = UNSET.toInt(),
    @Deprecated("Caffeine has no concurrency level; ignored since CoCache 5.")
    var concurrencyLevel: Int = UNSET.toInt(),
    var maximumSize: Long = UNSET,
    var expireUnit: TimeUnit = TimeUnit.SECONDS,
    var expireAfterWrite: Long = UNSET,
    var expireAfterAccess: Long = UNSET
) {
    companion object {
        const val UNSET = -1L
    }

    /**
     * [expireAfterWrite] bounds how long a local copy lives (CoCache's `localMaxAge`, which has no unlimited setting);
     * the other values size the local cache.
     */
    fun toCachePolicy(base: CachePolicy): CachePolicy {
        val local = base.local
        return base.toBuilder()
            .local(
                local.copy(
                    maximumSize = maximumSize.takeIf { it != UNSET } ?: local.maximumSize,
                    initialCapacity = initialCapacity.takeIf { it != UNSET.toInt() } ?: local.initialCapacity,
                    expireAfterAccess = expireAfterAccess.toDuration() ?: local.expireAfterAccess,
                )
            )
            .localMaxAge(expireAfterWrite.toDuration() ?: base.localMaxAge)
            .build()
    }

    private fun Long.toDuration(): Duration? {
        if (this == UNSET) {
            return null
        }
        return Duration.of(this, expireUnit.toChronoUnit())
    }
}
