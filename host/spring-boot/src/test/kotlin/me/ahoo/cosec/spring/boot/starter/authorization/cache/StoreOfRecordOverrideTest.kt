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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException

/**
 * Neither an application's [CacheSpecCustomizer] nor a `cocache.*` property can weaken the policy snapshot store, which
 * is the store of record: it never expires, and a failed Redis read throws. CoSec creates it through CoCache's factory
 * with locks ([CoSecCacheSpecs.locks]): a property that sets a locked field fails startup, a customizer that changes one
 * throws, the global layers skip it, and `unlock` (breaking the glass) cannot reach the fields CoSec pins.
 * The revocation tables read "not revoked" when Redis fails, but an operator may make them fail closed.
 */
@OptIn(InternalCoSecApi::class)
class StoreOfRecordOverrideTest {
    /** Weakens every store it is applied to. */
    private val weakening = object : CacheSpecCustomizer {
        override fun customize(spec: StoreSpec.Builder<*, *>) {
            spec.policy { ttl = Duration.ofMinutes(1) }
            spec.unknownRead(UnknownReadMode.ABSENT)
        }
    }

    private val snapshot = CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME

    private fun CacheRuntime.effective(cacheName: String, field: PolicyField<*>): String =
        checkNotNull(admin(cacheName)?.policyReport).entries.single { it.field == field.key }.value

    private val backend = HookedBackend(InMemoryBackend())
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            "cocache.backend=memory",
            "cosec.jwt.enabled=true",
            "cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean(CoherenceBackend::class.java, { backend })
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecTokenRevocationCacheAutoConfiguration::class.java,
            CoSecSnapshotAutoConfiguration::class.java,
            CoSecPipelineAutoConfiguration::class.java,
        )

    @Test
    fun aCustomizerCannotWeakenTheStoreOfRecord() {
        contextRunner.withBean(CacheSpecCustomizer::class.java, { weakening }).run { context ->
            assertThat(context).hasFailed()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { snapshot in it && CoSecCacheSpecs.LOCKED_BY in it }.assert().isTrue()
        }
    }

    private fun ApplicationContextRunner.refusedFor(why: String, vararg properties: String) =
        withPropertyValues(*properties).run { context ->
            assertThat(context).describedAs(properties.joinToString()).hasFailed()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { snapshot in it && "$why by ${CoSecCacheSpecs.LOCKED_BY}" in it }
                .assert().describedAs(properties.joinToString()).isTrue()
        }

    /**
     * Fails for the lock, not for some other reason: since CoCache 8.0.0-alpha.4 an unknown or inapplicable key under
     * `cocache.caches.[name]` fails startup too, which would pass a bare `hasFailed()`. A store of record's `ttl` and
     * `unknown-read` are pinned (CoCache 8.0.0-rc.1), its layout and local max age locked.
     */
    @Test
    fun aPerCachePropertyCannotSetALockedField() {
        listOf("ttl=60s", "unknown-read=absent").forEach {
            contextRunner.refusedFor("pinned", "cocache.caches.[$snapshot].$it")
        }
        listOf("key-prefix=elsewhere:", "local-max-age=1h").forEach {
            contextRunner.refusedFor("locked", "cocache.caches.[$snapshot].$it")
        }
    }

    /**
     * `unlock` is CoCache's break glass, but none reaches a pinned field: an entry that unlocks a store of record's `ttl`
     * or `unknown-read` fails startup, so the store stays one that never expires and throws on a failed read.
     */
    @Test
    fun breakingTheGlassStillLeavesAStoreOfRecord() {
        listOf("ttl" to "60s", "unknown-read" to "absent").forEach { (field, value) ->
            contextRunner.refusedFor(
                "pinned",
                "cocache.caches.[$snapshot].$field=$value",
                "cocache.caches.[$snapshot].unlock=$field",
            )
        }
        contextRunner
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.policy(snapshot)!!.ttl.assert().isNull()
                runtime.effective(snapshot, PolicyField.UNKNOWN_READ).assert().isEqualTo("throw")

                // and the store the starter created behaves that way
                val cache = context.getBean(PolicySnapshotCache::class.java)
                val store = context.getBean(RedisSnapshotDocumentStore::class.java)
                cache.put(SnapshotKey.Platform, SnapshotDocument(2, 1, Instant.EPOCH, PLATFORM.encodeToByteArray()))
                val admin = checkNotNull(runtime.admin(snapshot))
                admin.inspect(SnapshotKeyEncoder.PLATFORM).remote.ttl.assert().isNull()

                admin.clearLocal()
                backend.failAll = true
                try {
                    assertThrows<ExecutionException> { store.read(SnapshotKey.Platform).get() }
                        .cause.assert().isInstanceOf(CacheReadException::class.java)
                } finally {
                    backend.failAll = false
                }
            }
    }

    /**
     * CoSec publishes the name of every cache it may create (CoCache 8.0.0-rc.1's `CacheNameContributor`): an entry for
     * a control-plane cache, from a configuration every service shares, starts an instance that runs no control plane.
     * Only CoSec's names are relaxed: a misspelled one still fails startup as an undeclared cache.
     */
    @Test
    fun anEntryForACacheThisInstanceDoesNotCreateStarts() {
        val changes = CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME
        val runner = contextRunner.withUserConfiguration(CoSecAutoConfiguration::class.java)
        runner.withPropertyValues("cocache.caches.[$changes].local-max-age=10s").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(CacheRuntime::class.java).cacheNames.assert().doesNotContain(changes)
        }
        runner.withPropertyValues("cocache.caches.[ChangeRequestCach].local-max-age=10s").run { context ->
            assertThat(context).hasFailed()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { "undeclared caches [ChangeRequestCach]" in it }.assert().isTrue()
        }
    }

    /** The global layers skip what CoSec locks: one local max age, `cosec.authorization.snapshot.local-max-age`. */
    @Test
    fun globalLayersSkipLockedFields() {
        contextRunner
            .withPropertyValues(
                "cosec.authorization.snapshot.local-max-age=7s",
                "cocache.defaults.store.local-max-age=1h",
                "cocache.overrides.store.local-max-age=1s",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.policy(snapshot)!!.localMaxAge.assert().isEqualTo(Duration.ofSeconds(7))
                context.getBean(
                    PolicySnapshotCache::class.java
                ).localCopyMaxAge.assert().isEqualTo(Duration.ofSeconds(7))
                // A revocation table is not locked there: the override tightens it.
                runtime.policy(
                    CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME
                )!!.localMaxAge.assert().isEqualTo(Duration.ofSeconds(1))
            }
    }

    @Test
    fun revocationTablesReadAbsentUnlessToldToFailClosed() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.effective(
                CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME,
                PolicyField.UNKNOWN_READ
            ).assert().isEqualTo("absent")
            runtime.effective(
                CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME,
                PolicyField.UNKNOWN_READ
            ).assert().isEqualTo("absent")
        }
        contextRunner
            .withPropertyValues("cocache.caches.[${CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME}].unknown-read=throw")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(CacheRuntime::class.java)
                    .effective(
                        CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME,
                        PolicyField.UNKNOWN_READ
                    ).assert().isEqualTo("throw")
            }
    }

    /**
     * RC1-45: a customizer may tune a CoSec cache's policy within what CoSec locks, but never what decides its content:
     * a key filter on the revoked-token table would hide revocations, a TTL function would make a store of record
     * expire, and another codec would read other bytes. CoCache refuses a customizer that changes a locked layout field,
     * so the application fails to start rather than run with it.
     */
    @Test
    fun customizersCannotReplaceWhatDecidesACachesContent() {
        val changes = listOf<Pair<String, (StoreSpec.Builder<*, *>) -> Unit>>(
            "key filter" to { it.keyFilter { true } },
            "ttl function" to {
                @Suppress("UNCHECKED_CAST")
                (it as StoreSpec.Builder<Any, Any>).ttlOf { _, _ -> Duration.ofMillis(1) }
            },
            "codec" to {
                @Suppress("UNCHECKED_CAST")
                (it as StoreSpec.Builder<Any, Any>).codec(object : Codec<Any> {
                    override fun encode(value: Any): ByteArray = error("not CoSec's codec")

                    override fun decode(bytes: ByteArray): Any = error("not CoSec's codec")
                })
            },
        )
        changes.forEach { (what, change) ->
            val customizer = object : CacheSpecCustomizer {
                override fun supports(
                    cacheName: String
                ): Boolean = cacheName == CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME

                override fun customize(spec: StoreSpec.Builder<*, *>) = change(spec)
            }
            contextRunner.withBean(CacheSpecCustomizer::class.java, { customizer }).run { context ->
                assertThat(context).describedAs(what).hasFailed()
                generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                    .any { CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME in it }.assert().describedAs(what).isTrue()
            }
        }
    }

    private companion object {
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z"}"""
    }
}
