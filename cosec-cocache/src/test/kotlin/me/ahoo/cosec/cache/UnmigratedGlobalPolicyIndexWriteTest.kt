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
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.redis.migration.CoCache5MigrationOptions
import me.ahoo.cocache.redis.migration.CoCache5Migrator
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * A 7.0 instance publishes a policy while the global policy index is still the native SET CoSec 6.x wrote, against
 * real Redis. The index update fails (`ForeignFormatMode.FAIL` also guards `SetCache` writes), the SET is left as it
 * was, no request is allowed, and the migration still converts the index afterwards.
 */
class UnmigratedGlobalPolicyIndexWriteTest {
    private val connectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(connectionFactory)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val indexKey = "$keyPrefix:global:policy"
    private val data = CoCache5DataFixture(redis, keyPrefix).apply { write() }
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
    private fun instance() = CoSecTestCaches(backend, keyPrefix).also { instances.add(it) }

    private fun migrate() {
        CoSecCoCache5Migration(
            migrator = {
                CoCache5Migrator.standalone(
                    RedisURI.create("redis://localhost:6379"),
                    CoCache5MigrationOptions(backupPrefix = "$keyPrefix-backup:"),
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
        ).migrate()
    }

    /** A principal whose own policy allows everything: only the global deny stands in the way. */
    private fun evaluate(caches: CoSecTestCaches): SecurityStatus {
        val principal = SimplePrincipal("alice", policies = setOf(data.customAllow.id))
        val pipeline = SecurityPipeline(
            object : RequestParser<String> {
                override fun parse(request: String): Request =
                    EvaluateRequest(path = request, headers = mapOf("CoSec-App-Id" to "app"))
            },
            SecurityContextParser { SimpleSecurityContext(principal) },
            SimpleAuthorization(caches.policyRepository(), caches.appRolePermissionRepository()),
        )
        return pipeline.evaluate("/orders/1").block()!!.status
    }

    private fun assertFailsOnForeignFormat(publish: () -> Unit) {
        val error = assertThrows<CacheWriteException> { publish() }
        error.cause.assert().isInstanceOf(ForeignFormatException::class.java)
    }

    private fun assertIndexUntouched() {
        redis.type(indexKey).assert().isEqualTo(DataType.SET)
        redis.opsForSet().members(indexKey).assert().isEqualTo(setOf(data.globalDeny.id))
    }

    @Test
    fun publishingGlobalPolicyFailsAndKeepsTheUnmigratedIndex() {
        val globalAllow = PolicyData(
            "global-allow",
            "c",
            "global-allow",
            "",
            PolicyType.GLOBAL,
            "t",
            statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
        )
        val caches = instance()

        assertFailsOnForeignFormat { caches.policyRepository().setPolicy(globalAllow).block() }

        assertIndexUntouched()
        evaluate(caches).assert().isEqualTo(SecurityStatus.ERROR)
        evaluate(instance()).assert().isEqualTo(SecurityStatus.ERROR)

        // the migration converts the index as 6.x left it: the failed publish never reached it
        migrate()
        instance().policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
        evaluate(instance()).assert().isEqualTo(SecurityStatus.FORBIDDEN)

        // repeating the publish after the migration succeeds
        instance().policyRepository().setPolicy(globalAllow).block()
        instance().policyRepository().getGlobalPolicy().block()!!.toSet()
            .assert().isEqualTo(setOf(data.globalDeny, globalAllow))
    }

    @Test
    fun republishingGlobalPolicyAsCustomFailsAndKeepsTheUnmigratedIndex() {
        val demoted = PolicyData(
            data.globalDeny.id,
            data.globalDeny.category,
            data.globalDeny.name,
            data.globalDeny.description,
            PolicyType.CUSTOM,
            data.globalDeny.tenantId,
            statements = data.globalDeny.statements,
        )
        val caches = instance()

        assertFailsOnForeignFormat { caches.policyRepository().setPolicy(demoted).block() }

        assertIndexUntouched()
        // the content is written only after the index update, so it is still the 6.x value
        redis.opsForValue().get("$keyPrefix:policy:${data.globalDeny.id}")
            .assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(data.globalDeny))
        evaluate(caches).assert().isEqualTo(SecurityStatus.ERROR)

        // the global deny is migrated as global and still applies
        migrate()
        instance().policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(data.globalDeny))
        evaluate(instance()).assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }
}
