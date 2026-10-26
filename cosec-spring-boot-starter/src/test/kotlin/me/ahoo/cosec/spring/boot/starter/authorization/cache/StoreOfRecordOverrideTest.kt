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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * Neither an application's [CacheSpecCustomizer] nor `cocache.caches.[name].*` can weaken the store-of-record policy
 * of the policy, global policy index, app permission and role permission caches: they never expire, a failed read
 * throws, and unreadable content fails instead of being reclaimed (which, with no loader, would delete the only copy).
 * The revoked token cache keeps its expiry and degraded reads, but never reclaims a revocation either.
 */
class StoreOfRecordOverrideTest {
    /** Weakens every cache it is applied to. */
    private val weakening = object : CacheSpecCustomizer {
        override fun customize(spec: CacheSpec.Builder<*, *>) {
            spec.policy(weaken(spec.policy))
        }

        override fun customize(spec: SetCacheSpec.Builder<*, *>) {
            spec.policy(weaken(spec.policy))
        }

        private fun weaken(policy: CachePolicy) = policy.toBuilder()
            .ttl(Duration.ofMinutes(1))
            .readFailure(ReadFailureMode.DEGRADE)
            .foreignFormat(ForeignFormatMode.RECLAIM)
            .build()
    }

    /**
     * The store-of-record caches the starter creates. The snapshot cache is not wired before RFC 0003 PR-5, and
     * CoCache refuses properties for caches nobody declares; StrictCacheFailureTest assembles it.
     */
    private val wired = CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES - CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME

    private val revocationCaches = listOf(
        CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME,
        CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME
    )

    private val weakeningProperties = (wired + revocationCaches).flatMap { name ->
        listOf(
            "cocache.caches.$name.ttl=60s",
            "cocache.caches.$name.read-failure=degrade",
            "cocache.caches.$name.foreign-format=reclaim",
        )
    }

    private fun assertStoreOfRecord(policy: CachePolicy) {
        policy.ttl.assert().isNull()
        policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
    }

    /** The policy of the engine the runtime actually created, not of a separately assembled spec. */
    private fun CacheRuntime.createdPolicy(cacheName: String): CachePolicy {
        val engine = checkNotNull(admin(cacheName)) { "Cache [$cacheName] was not created." }
        val field = generateSequence<Class<*>>(engine.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == "policy" && it.type == CachePolicy::class.java }
        field.isAccessible = true
        return field.get(engine) as CachePolicy
    }

    private val backend = HookedBackend(InMemoryBackend())
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            *(
                listOf(
                    "cocache.backend=memory",
                    "cocache.defaults.foreign-format=reclaim",
                    "cosec.jwt.enabled=true",
                    "cosec.jwt.token-revocation.enabled=true",
                ) + weakeningProperties
                ).toTypedArray()
        )
        .withBean(CoherenceBackend::class.java, { backend })
        .withBean(CacheSpecCustomizer::class.java, { weakening })
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecPolicyCacheAutoConfiguration::class.java,
            CoSecPermissionCacheAutoConfiguration::class.java,
            CoSecTokenRevocationCacheAutoConfiguration::class.java,
        )

    @Test
    fun createdCachesKeepThePinnedPolicy() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.cacheNames.assert().containsAll(wired)
            wired.forEach { assertStoreOfRecord(runtime.createdPolicy(it)) }
            // revoked tokens and subjects keep their expiry and degraded reads, but never reclaim a revocation
            revocationCaches.forEach {
                val revoked = runtime.createdPolicy(it)
                revoked.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
                revoked.ttl.assert().isEqualTo(Duration.ofMinutes(1))
                revoked.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
            }
        }
    }

    @Test
    fun customizersAndPerCachePropertiesCannotOverrideStoreOfRecord() {
        contextRunner
            .run { context ->
                assertThat(context).hasNotFailed()
                // the properties and the customizer do weaken the policy they are given...
                val properties = context.getBean(CoCacheProperties::class.java)
                val weakened = properties.policyFor(
                    CoSecCacheSpecs.POLICY_CACHE_NAME,
                    CachePolicy.DEFAULT
                )
                weakened.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
                weakened.foreignFormat.assert().isEqualTo(ForeignFormatMode.RECLAIM)

                // ...but CoSec applies storeOfRecord() after both
                val assembler = CoSecCacheAssembler(
                    context.getBean(CacheRuntime::class.java),
                    listOf(weakening),
                    properties,
                )
                assertStoreOfRecord(
                    assembler.assemble(CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer)))
                        .policy
                )
                assertStoreOfRecord(assembler.assemble(CoSecCacheSpecs.globalPolicyIndex()).policy)

                // and the caches the starter created behave that way
                val runtime = context.getBean(CacheRuntime::class.java)
                val policyCache = context.getBean(PolicyCache::class.java)
                val index = context.getBean(GlobalPolicyIndexCache::class.java)
                policyCache.put("p", PolicyData("p", "c", "n", "", PolicyType.GLOBAL, "t"))
                index.add(GlobalPolicyIndexCache.CACHE_KEY, listOf("p"))
                checkNotNull(runtime.admin(CoSecCacheSpecs.POLICY_CACHE_NAME)).inspect("p").remote.ttl.assert().isNull()
                checkNotNull(runtime.admin(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME))
                    .inspect(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY).remote.ttl.assert().isNull()

                runtime.admin(CoSecCacheSpecs.POLICY_CACHE_NAME)!!.clearLocal()
                runtime.admin(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME)!!.clearLocal()
                backend.failAll = true
                assertThrows<CacheReadException> { policyCache["p"] }
                assertThrows<RuntimeException> { index[GlobalPolicyIndexCache.CACHE_KEY] }
            }
    }
}
