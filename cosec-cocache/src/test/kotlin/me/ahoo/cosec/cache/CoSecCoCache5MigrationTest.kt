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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.redis.migration.CoCache5MigrationOptions
import me.ahoo.cocache.redis.migration.CoCache5MigrationReport
import me.ahoo.cocache.redis.migration.CoCache5Migrator
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.kotlin.test.test
import java.time.Duration

/**
 * CoSec 6.x data (CoCache 5) is migrated in place to CoCache 6 and rolled back, against real Redis.
 */
class CoSecCoCache5MigrationTest {
    private val connectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(connectionFactory)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val backupPrefix = "$keyPrefix-backup:"
    private val data = CoCache5DataFixture(redis, keyPrefix)
    private val backend = CoSecTestCaches.redisBackend()
    private val instances = mutableListOf<CoSecTestCaches>()

    @AfterEach
    fun destroy() {
        instances.forEach { it.close() }
        backend.close()
        redis.delete(redis.keys("$keyPrefix*"))
        connectionFactory.destroy()
    }

    /** A 7.0 instance started now: nothing cached locally. */
    private fun instance(keyPrefix: String = this.keyPrefix) =
        CoSecTestCaches(backend, keyPrefix).also { instances.add(it) }

    private fun migration(keyPrefix: String = this.keyPrefix): CoSecCoCache5Migration {
        return CoSecCoCache5Migration(
            migrator = {
                CoCache5Migrator.standalone(
                    RedisURI.create("redis://localhost:6379"),
                    CoCache5MigrationOptions(backupPrefix = backupPrefix),
                )
            },
            redisTemplate = redis,
            policySpec = CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix),
            globalPolicyIndexSpec = CoSecCacheSpecs.globalPolicyIndex(keyPrefix),
            appPermissionSpec = CoSecCacheSpecs.appPermission(
                CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer),
                keyPrefix
            ),
            revokedTokenSpec = CoSecCacheSpecs.revokedToken(
                CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer),
                keyPrefix
            ),
            legacyKeyPrefix = keyPrefix,
        )
    }

    private fun PolicyData.renamed(id: String, name: String = id) =
        PolicyData(id, category, name, description, type, tenantId, condition, statements)

    private fun written(report: CoCache5MigrationReport): Map<String, Long> =
        report.caches.associate { it.name to it.written }

    @Test
    fun dryRunMigrateRollbackAndDropBackups() {
        data.write()
        val expected = mapOf(
            CoSecCacheSpecs.POLICY_CACHE_NAME to 3L,
            CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME to 1L,
            CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME to 1L,
            CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME to 1L,
        )

        // the dry run writes nothing
        written(migration().dryRun()).assert().isEqualTo(expected)
        redis.opsForValue().get("$keyPrefix:policy:custom-allow")
            .assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(data.customAllow))

        val report = migration().migrate()
        written(report).assert().isEqualTo(expected)
        // the global index is migrated last
        report.caches.last().name.assert().isEqualTo(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME)
        report.cache(CoSecCacheSpecs.POLICY_CACHE_NAME)!!.negatives.assert().isEqualTo(1L)

        val migrated = instance()
        migrated.policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
        migrated.policyRepository().getPolicies(setOf("custom-allow", "missing")).block()
            .assert().isEqualTo(listOf(data.customAllow))
        migrated.appPermissionCache["app"].assert().isEqualTo(data.appPermission)
        CoCacheTokenStore(migrated.revokedTokenCache).isRevoked("jti").assert().isTrue()
        checkNotNull(migrated.runtime.admin(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME)).inspect("jti").remote.ttl!!
            .assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))
        // not CoCache data, or not this migration's: left as they were
        redis.type("$keyPrefix:policy:history:custom-allow").assert().isEqualTo(DataType.LIST)
        redis.opsForValue().get("$keyPrefix:policy:history:custom-allow:seq").assert().isEqualTo("1")
        redis.type("$keyPrefix:role:permission:admin").assert().isEqualTo(DataType.SET)

        // running it again changes nothing
        written(migration().migrate()).values.assert().containsOnly(0L)

        // rollback restores the 6.x values
        migration().rollback()
        redis.opsForValue().get("$keyPrefix:policy:custom-allow")
            .assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(data.customAllow))
        redis.opsForSet().members("$keyPrefix:global:policy").assert().isEqualTo(setOf("global-deny"))
        redis.keys("$backupPrefix*").assert().isEmpty()

        // migrate again, then drop the backups: rollback is no longer possible
        migration().migrate()
        redis.keys("$backupPrefix*").assert().isNotEmpty()
        migration().dropBackups()
        redis.keys("$backupPrefix*").assert().isEmpty()
        instance().policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
    }

    @Test
    fun policyIdStartingWithHistoryIsMigrated() {
        data.write()
        val historyViewer = data.globalDeny.renamed("history-viewer")
        redis.opsForValue().set(
            "$keyPrefix:policy:history-viewer",
            CoSecJsonSerializer.writeValueAsString(historyViewer)
        )
        redis.opsForSet().add("$keyPrefix:global:policy", historyViewer.id)

        migration().migrate().cache(CoSecCacheSpecs.POLICY_CACHE_NAME)!!.written.assert().isEqualTo(4L)

        instance().policyRepository().getGlobalPolicy().block()!!.toSet()
            .assert().isEqualTo(setOf(data.globalDeny, historyViewer))
        // the revision history itself is still left alone
        redis.type("$keyPrefix:policy:history:custom-allow").assert().isEqualTo(DataType.LIST)
    }

    @Test
    fun keyPrefixEndingWithColonIsMigratedFromTheKeys6xUsed() {
        // 6.x appended ":policy:" to the prefix untrimmed: "p:" stored "p::policy:x". 7.0 reads "p:policy:x".
        val colonPrefix = "$keyPrefix:"
        val colonData = CoCache5DataFixture(redis, colonPrefix)
        colonData.write()
        redis.hasKey("$keyPrefix::policy:global-deny").assert().isTrue()

        val report = migration(colonPrefix).migrate()
        written(report)[CoSecCacheSpecs.POLICY_CACHE_NAME].assert().isEqualTo(3L)
        written(report)[CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME].assert().isEqualTo(1L)
        // copied to the new keys: the 6.x keys are their own backup
        report.caches.sumOf { it.backedUp }.assert().isEqualTo(0L)

        val migrated = instance(colonPrefix)
        migrated.policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(colonData.globalDeny))
        migrated.appPermissionCache["app"].assert().isEqualTo(colonData.appPermission)
        CoCacheTokenStore(migrated.revokedTokenCache).isRevoked("jti").assert().isTrue()
        redis.opsForValue().get("$keyPrefix::policy:custom-allow")
            .assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(colonData.customAllow))

        // rollback deletes the new keys; the 6.x keys were never changed
        migration(colonPrefix).rollback()
        redis.hasKey("$keyPrefix:policy:global-deny").assert().isFalse()
        redis.hasKey("$keyPrefix:global:policy").assert().isFalse()
        redis.opsForSet().members("$keyPrefix::global:policy").assert().isEqualTo(setOf("global-deny"))
    }

    @Test
    fun cachedMissInGlobalIndexBecomesEmptyIndex() {
        // 6.x cached a miss of the whole index (no global policy yet) as a set holding only the sentinel.
        redis.opsForSet().add("$keyPrefix:global:policy", CoCache5DataFixture.MISSING_GUARD)

        migration().migrate().cache(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME)!!.negatives.assert().isEqualTo(1L)

        val migrated = instance()
        migrated.policyRepository().getGlobalPolicy().block().assert().isEqualTo(emptyList<Any>())
        // a 7.0 publish works on the migrated index
        migrated.policyRepository().setPolicy(data.globalDeny).block()
        instance().policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
    }

    @Test
    fun indexedPolicyCachedAsMissingFailsClosedAfterMigration() {
        data.write()
        // the index lists "missing", whose content 6.x cached as a miss: 6.x skipped it, 7.0 denies
        redis.opsForSet().add("$keyPrefix:global:policy", "missing")

        migration().migrate()

        instance().policyRepository().getGlobalPolicy()
            .test()
            .expectErrorMatches { it is MissingPolicyException && it.policyIds == setOf("missing") }
            .verify()
    }

    @Test
    fun rollbackAfter70HasWritten() {
        data.write()
        migration().migrate()
        val running = instance().policyRepository()
        val changed = data.customAllow.renamed(data.customAllow.id, "changed by 7.0")
        val created = data.globalDeny.renamed("created-after")
        running.setPolicy(changed).block()
        running.setPolicy(created).block()

        migration().rollback()

        // migrated keys are restored from their backups: 7.0's change is lost
        redis.opsForValue().get("$keyPrefix:policy:custom-allow")
            .assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(data.customAllow))
        // the index is restored too: the policy 7.0 made global is no longer in it
        redis.opsForSet().members("$keyPrefix:global:policy").assert().isEqualTo(setOf("global-deny"))
        // a policy created after the migration has no backup and stays in the CoCache 6 format, unreadable by 6.x
        redis.type("$keyPrefix:policy:created-after").assert().isEqualTo(DataType.STRING)
        val stored = redis.opsForValue().get("$keyPrefix:policy:created-after")
        runCatching { CoSecJsonSerializer.readValue(stored, PolicyData::class.java) }.isFailure.assert().isTrue()
        instance().policyCache["created-after"].assert().isEqualTo(created)
    }

    @Test
    fun nothingToMigrate() {
        written(migration().migrate()).values.assert().containsOnly(0L)
    }
}
