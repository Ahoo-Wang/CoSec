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
import me.ahoo.cocache.redis.migration.CoCache5Migrator
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 6.x data that 7.0 would not read at all, because it sits under other keys, stops startup until the migration has
 * written the schema marker, against real Redis. Before the marker, 7.0 read the global deny of these layouts as
 * "no global policy".
 */
class CoSecSchemaMarkerTest {
    private val connectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(connectionFactory)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val backend = CoSecTestCaches.redisBackend()
    private val instances = mutableListOf<CoSecTestCaches>()

    @AfterEach
    fun destroy() {
        instances.forEach { it.close() }
        backend.close()
        redis.delete(redis.keys("$keyPrefix*"))
        connectionFactory.destroy()
    }

    private fun instance(keyPrefix: String) = CoSecTestCaches(backend, keyPrefix).also { instances.add(it) }

    private fun migration(legacyKeyPrefix: String, keyPrefix: String = legacyKeyPrefix) = CoSecCoCache5Migration(
        migrator = {
            CoCache5Migrator.standalone(
                RedisURI.create("redis://localhost:6379"),
                CoCache5MigrationOptions(backupPrefix = "${this.keyPrefix}-backup:"),
            )
        },
        redisTemplate = redis,
        policySpec = CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix),
        globalPolicyIndexSpec = CoSecCacheSpecs.globalPolicyIndex(keyPrefix),
        appPermissionSpec = CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix),
        revokedTokenSpec = CoSecCacheSpecs.revokedToken(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix),
        legacyKeyPrefix = legacyKeyPrefix,
        keyPrefix = keyPrefix,
    )

    @Test
    fun keyPrefixEndingWithColon() {
        // 6.x stored "p::policy:x"; 7.0 reads "p:policy:x" and found nothing there
        val colonPrefix = "$keyPrefix:"
        val data = CoCache5DataFixture(redis, colonPrefix).apply { write() }
        val marker = CoSecSchemaMarker(redis, colonPrefix)
        marker.key.assert().isEqualTo("$keyPrefix:meta:schema")

        val error = assertThrows<CoSecSchemaException> { marker.verify() }
        error.message.assert().contains("$keyPrefix::")
        marker.isMarked().assert().isFalse()

        migration(colonPrefix).migrate()
        marker.verify()
        instance(colonPrefix).policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))

        // a rollback deletes the 7.0 copies and the marker: 7.0 refuses to start again
        migration(colonPrefix).rollback()
        assertThrows<CoSecSchemaException> { marker.verify() }
    }

    @Test
    fun changedKeyPrefix() {
        val legacyPrefix = "$keyPrefix:old"
        val newPrefix = "$keyPrefix:new"
        val data = CoCache5DataFixture(redis, legacyPrefix).apply { write() }
        val marker = CoSecSchemaMarker(redis, newPrefix, legacyPrefix)

        assertThrows<CoSecSchemaException> { marker.verify() }

        migration(legacyPrefix, newPrefix).migrate()
        marker.isMarked().assert().isTrue()
        marker.verify()
        val migrated = instance(newPrefix)
        migrated.policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
        CoCacheTokenStore(migrated.revokedTokenCache).isRevoked("jti").assert().isTrue()
    }

    @Test
    fun unmigratedDataUnderTheSameKeys() {
        CoCache5DataFixture(redis, keyPrefix).write()
        assertThrows<CoSecSchemaException> { CoSecSchemaMarker(redis, keyPrefix).verify() }
    }

    @Test
    fun onlyRoleGrantsOrRevocationsLeft() {
        redis.opsForValue().set("$keyPrefix:token:revoked:jti", "true")
        assertThrows<CoSecSchemaException> { CoSecSchemaMarker(redis, keyPrefix).verify() }
        redis.delete("$keyPrefix:token:revoked:jti")
        redis.opsForSet().add("$keyPrefix:role:permission:admin", "app.manage")
        assertThrows<CoSecSchemaException> { CoSecSchemaMarker(redis, keyPrefix).verify() }
    }

    @Test
    fun newDeployment() {
        // other data under the prefix, such as API keys or rate limit counters, is not CoSec 6.x cache data
        redis.opsForValue().set("$keyPrefix:limiter:rule", "1")
        val marker = CoSecSchemaMarker(redis, keyPrefix)
        marker.findDataKey().assert().isNull()

        marker.verify()

        marker.isMarked().assert().isTrue()
        redis.opsForValue().get(marker.key).assert().isEqualTo(CoSecSchemaMarker.SCHEMA_VERSION)
        // 7.0 writes from now on do not trip the check
        instance(keyPrefix).policyRepository().setPolicy(CoCache5DataFixture(redis, keyPrefix).globalDeny).block()
        marker.verify()
    }

    @Test
    fun unknownSchemaVersion() {
        val marker = CoSecSchemaMarker(redis, keyPrefix)
        redis.opsForValue().set(marker.key, "8")
        assertThrows<CoSecSchemaException> { marker.verify() }
    }

    @Test
    fun migrationNeedsTheKeyPrefixOfItsSpecs() {
        assertThrows<IllegalArgumentException> {
            CoSecCoCache5Migration(
                migrator = { error("not opened") },
                redisTemplate = redis,
                policySpec = CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix),
                globalPolicyIndexSpec = CoSecCacheSpecs.globalPolicyIndex(keyPrefix),
                appPermissionSpec = CoSecCacheSpecs.appPermission(
                    CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer),
                    keyPrefix
                ),
                legacyKeyPrefix = keyPrefix,
                keyPrefix = "$keyPrefix:other",
            )
        }
    }
}
