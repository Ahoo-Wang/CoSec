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

package me.ahoo.cosec.cache

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.control.ControlCacheSpecs
import me.ahoo.cosec.cache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Policy and permission caches are the store of record in Redis, so they must be `Store`s that never expire and whose
 * failed Redis reads throw, whatever configuration says. Guards against CoCache's defaults or the application's
 * `cocache.*` properties and customizers (`ttl`, `unknown-read: absent`) silently applying to them.
 */
class CacheTtlPolicyTest {
    private val storeOfRecordSpecs: Map<String, StoreSpec<*, *>> = mapOf(
        CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME to CoSecCacheSpecs.policySnapshot(),
        // RFC 0007 §5.2: the control plane's records, indexes, back-references and leases.
        CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME to ControlCacheSpecs.revisionRecord(),
        CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME to ControlCacheSpecs.revisionIndex(),
        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME to ControlCacheSpecs.referenceIndex(),
        CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME to ControlCacheSpecs.lease(),
        CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME to ControlCacheSpecs.list(),
        // RC1-4: an app list read as absent would leave the grant analysis trusting nothing, never more; it still throws.
        CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME to ControlCacheSpecs.appList(),
        CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME to ControlCacheSpecs.changeRequest(),
        CoSecCacheSpecs.STAGE_CACHE_NAME to ControlCacheSpecs.stage(),
        // RFC 0007 §6.4: a staged candidate is the control plane's only copy while its change stages.
        CoSecCacheSpecs.CANDIDATE_CACHE_NAME to ShadowCacheSpecs.candidate(),
    )

    /**
     * Layers that weaken every field CoSec constrains, as a `cocache.caches.[name]` entry with `unlock`, or a customizer,
     * would try: a finite `ttl` and absent reads for a store, no `ttl`, jitter and degraded reads for a cache. CoSec's
     * constraints run after them.
     */
    private object Weakening : CacheLayers {
        override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
            CacheLayers.NONE.apply(
                spec.toBuilder().unknownRead(UnknownReadMode.ABSENT).policy {
                    ttl = Duration.ofMinutes(1)
                    readTimeout = Duration.ofMillis(50)
                }.build(),
                locks,
            )

        override fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
            CacheLayers.NONE.apply(
                spec.toBuilder().unknownRead(UnknownReadMode.ABSENT).policy {
                    ttl = null
                    ttlJitter = Duration.ofMinutes(1)
                    readFailure = ReadFailureMode.DEGRADE
                }.build(),
                locks,
            )
    }

    private fun <T> weakened(block: (CoCacheFactory) -> T): T =
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { block(CoCacheFactory(it, Weakening)) }

    private fun CoCacheFactory.effective(name: String, field: PolicyField<*>): String =
        checkNotNull(runtime.admin(name)?.policyReport).entries.single { it.field == field.key }.value

    @Test
    fun storeOfRecordCachesNeverExpireAndFailClosed() {
        storeOfRecordSpecs.keys.assert().isEqualTo(CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES)
        weakened { factory ->
            storeOfRecordSpecs.forEach { (name, spec) ->
                factory.create(spec, CoSecCacheSpecs.locks(name)).assert().isInstanceOf(Store::class.java)
                factory.runtime.policy(name)!!.ttl.assert().describedAs(name).isNull()
                factory.effective(name, PolicyField.UNKNOWN_READ).assert().describedAs(name).isEqualTo("throw")
                CoSecCacheSpecs.locks(name).let { locks ->
                    locks.isLocked(PolicyField.TTL).assert().isTrue()
                    locks.isLocked(PolicyField.UNKNOWN_READ).assert().isTrue()
                    LayoutField.entries.forEach { locks.isLocked(it).assert().describedAs("$name $it").isTrue() }
                }
            }
        }
    }

    /** A store of record must be a `Store`: a `Cache` would reclaim content it cannot read and retry failed deletes. */
    @Test
    fun storeOfRecordLocksRefuseAnyOtherType() {
        val asCache = CacheSpec.builder<String, String>(CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME)
            .codec(me.ahoo.cocache.core.codec.StringCodec)
            .externallySourced()
            .build()
        weakened { factory ->
            assertThrows<IllegalArgumentException> {
                factory.create(asCache, CoSecCacheSpecs.locks(asCache.name))
            }
        }
        assertThrows<IllegalArgumentException> { CoSecCacheSpecs.locks("NotCoSecs") }
    }

    /**
     * The storage path stays what it was before CoCache 8 (`{prefix}:control:revision:...`), split at its last `:` into
     * CoCache's key prefix and a namespace without `:`.
     */
    @Test
    fun controlCachesLiveUnderTheirOwnStoragePaths() {
        fun Spec<*, *>.path() = keyPrefix + namespace
        ControlCacheSpecs.revisionRecord().path().assert().isEqualTo("cosec:control:revision")
        ControlCacheSpecs.revisionRecord().keyPrefix.assert().isEqualTo("cosec:control:")
        ControlCacheSpecs.revisionRecord().namespace.assert().isEqualTo("revision")
        ControlCacheSpecs.revisionIndex("other:").path().assert().isEqualTo("other:control:revisions")
        ControlCacheSpecs.referenceIndex().path().assert().isEqualTo("cosec:control:refs")
        ControlCacheSpecs.lease().path().assert().isEqualTo("cosec:control:lease")
        ControlCacheSpecs.list().path().assert().isEqualTo("cosec:control:list")
        ControlCacheSpecs.appList().path().assert().isEqualTo("cosec:control:apps")
        ControlCacheSpecs.changeRequest().path().assert().isEqualTo("cosec:control:change")
        ControlCacheSpecs.stage().path().assert().isEqualTo("cosec:control:stage")
    }

    /** RFC 0007 §6.4, §6.5: candidates are kept like policies; reports and statuses expire, but never read a failure as empty. */
    @Test
    fun shadowCachesLiveUnderTheirStoragePathsAndFailClosed() {
        fun Spec<*, *>.path() = keyPrefix + namespace
        ShadowCacheSpecs.candidate().path().assert().isEqualTo("cosec:candidate")
        ShadowCacheSpecs.reports().path().assert().isEqualTo("cosec:shadow:report")
        ShadowCacheSpecs.statuses().path().assert().isEqualTo("cosec:shadow:status")
        ShadowCacheSpecs.reporters().path().assert().isEqualTo("cosec:shadow:reporters")
        ShadowCacheSpecs.instances().path().assert().isEqualTo("cosec:shadow:instances")
        ShadowCacheSpecs.reporters().policy.ttl.assert().isEqualTo(ShadowCacheSpecs.REPORT_TTL)
        ShadowCacheSpecs.instances().policy.ttl.assert().isEqualTo(ShadowCacheSpecs.REPORT_TTL)
        ShadowCacheSpecs.reports().policy.ttl.assert().isEqualTo(ShadowCacheSpecs.REPORT_TTL)
        ShadowCacheSpecs.statuses().policy.ttl.assert().isEqualTo(ShadowCacheSpecs.STATUS_TTL)
        weakened { factory ->
            listOf(
                ShadowCacheSpecs.reports(),
                ShadowCacheSpecs.reporters(),
                ShadowCacheSpecs.statuses(),
                ShadowCacheSpecs.instances()
            ).forEach { spec ->
                factory.create(spec, CoSecCacheSpecs.locks(spec.name))
                factory.effective(
                    spec.name,
                    PolicyField.UNKNOWN_READ
                ).assert().describedAs(spec.name).isEqualTo("throw")
                // Review L1 of #1203: the lifetimes stay the declared ones, as on main.
                factory.runtime.policy(spec.name)!!.ttl.assert().describedAs(spec.name).isEqualTo(spec.policy.ttl)
                CoSecCacheSpecs.locks(spec.name).isLocked(PolicyField.TTL).assert().isTrue()
            }
        }
    }

    /**
     * Review L2 of #1203: a read that times out reads "not revoked" (`unknown-read: absent`, PIP-7), so no layer may give
     * the revocation tables a read timeout shorter than the store's command timeout.
     */
    @Test
    fun revocationTablesReadForTheWholeCommandTimeout() {
        weakened { factory ->
            listOf(CoSecCacheSpecs.revokedToken(), CoSecCacheSpecs.revokedSubject()).forEach { spec ->
                factory.create(spec, CoSecCacheSpecs.locks(spec.name))
                factory.runtime.policy(spec.name)!!.readTimeout.assert().describedAs(spec.name).isNull()
                factory.effective(spec.name, PolicyField.READ_TIMEOUT).assert().isEqualTo("command-timeout")
                CoSecCacheSpecs.locks(spec.name).isLocked(PolicyField.READ_TIMEOUT).assert().isTrue()
            }
        }
    }

    /** Review requirement: the snapshot store is a store of record like the others, with one local max age. */
    @Test
    fun snapshotCacheIsStoreOfRecordAndKeepsItsLocalCopiesShort() {
        val spec = CoSecCacheSpecs.policySnapshot()
        spec.policy.ttl.assert().isNull()
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.THROW)
        spec.policy.localMaxAge.assert().isEqualTo(CoSecCacheSpecs.DEFAULT_SNAPSHOT_LOCAL_MAX_AGE)
        spec.keyPrefix.assert().isEqualTo("cosec:")
        spec.namespace.assert().isEqualTo("snapshot")
        CoSecCacheSpecs.policySnapshot("other:", localMaxAge = Duration.ofSeconds(5)).let {
            (it.keyPrefix + it.namespace).assert().isEqualTo("other:snapshot")
            it.policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        }
        CoSecCacheSpecs.locks(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME).isLocked(PolicyField.LOCAL_MAX_AGE)
            .assert().isTrue()
    }

    /**
     * RC1-45 (`SNP-3`): a snapshot store built outside the starter, from a spec that expires, reads failures as absent,
     * filters keys, sets per-entry lifetimes and brings its own codec, is still a store of record:
     * `createPolicySnapshotCache` pins it, whoever built the spec.
     */
    @Test
    fun aSnapshotCacheBuiltOutsideTheStarterIsPinned() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val spec = CoSecCacheSpecs.policySnapshot("outside:").toBuilder()
                .unknownRead(UnknownReadMode.ABSENT)
                .policy { ttl = Duration.ofMinutes(1) }
                .keyFilter { true }
                .ttlOf { _, _ -> Duration.ofMillis(1) }
                .codec(object : Codec<SnapshotDocument> {
                    override fun encode(value: SnapshotDocument): ByteArray = error("not CoSec's codec")

                    override fun decode(bytes: ByteArray): SnapshotDocument = error("not CoSec's codec")
                })
                .build()
            val cache = CoCacheFactory(runtime).createPolicySnapshotCache(spec)
            val factory = CoCacheFactory(runtime)
            runtime.policy(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.ttl.assert().isNull()
            factory.effective(
                CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME,
                PolicyField.UNKNOWN_READ
            ).assert().isEqualTo("throw")
            cache.storageKey(SnapshotKey.Platform).assert().isEqualTo("outside:snapshot:platform")
            // The filter that called every key absent, the TTL function and the foreign codec are gone: a written
            // document reads back, and never expires in Redis.
            val body = """{"schema":2,"revision":1,"publishedAt":"2026-11-03T00:00:00Z","policies":[],"bindings":[]}"""
            val document = SnapshotDocument(2, 1, Instant.parse("2026-11-03T00:00:00Z"), body.encodeToByteArray())
            cache.put(SnapshotKey.Platform, document)
            runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.clearLocal()
            cache.get(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
            runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.inspect("platform").remote.ttl.assert().isNull()
        }
    }

    @Test
    fun revokedTokenCacheBoundsMissingTtlAndReadsAbsentWhenRedisFails() {
        val spec = CoSecCacheSpecs.revokedToken()
        spec.policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
    }

    /**
     * The absent read is CoSec's declaration, not a lock: an operator can still make the revocation check fail closed
     * (`cocache.caches.[RevokedTokenCache].unknown-read: throw`).
     */
    @Test
    fun revokedSubjectCacheIsConfiguredLikeRevokedTokens() {
        val spec = CoSecCacheSpecs.revokedSubject()
        spec.policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
        (spec.keyPrefix + spec.namespace).assert().isEqualTo("cosec:token:subject:revoked")
        listOf(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME).forEach { name ->
            CoSecCacheSpecs.locks(name).isLocked(PolicyField.UNKNOWN_READ).assert().isFalse()
            CoSecCacheSpecs.locks(name).isLocked(LayoutField.KEY_PREFIX).assert().isTrue()
        }
    }

    /**
     * RFC 0006 §5.5.2: the memberships cache expires by design, but never degrades a failed read into "no memberships",
     * and it can never be made to live forever: a lost invalidation would then never apply.
     */
    @Test
    fun membershipCacheExpiresButFailsClosed() {
        val spec = CoSecCacheSpecs.membership(ttl = Duration.ofSeconds(5))
        spec.externallySourced.assert().isTrue()
        spec.policy.ttl.assert().isEqualTo(Duration.ofSeconds(5))
        spec.policy.ttlJitter.assert().isEqualTo(Duration.ZERO)
        spec.policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        (spec.keyPrefix + spec.namespace).assert().isEqualTo("cosec:membership")

        weakened { factory ->
            factory.create(spec, CoSecCacheSpecs.locks(spec.name))
            factory.runtime.policy(spec.name)!!.let {
                it.ttl.assert().isEqualTo(CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL)
                it.ttlJitter.assert().isEqualTo(Duration.ZERO)
                it.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
            }
            factory.effective(spec.name, PolicyField.UNKNOWN_READ).assert().isEqualTo("throw")
        }
        CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES.assert().doesNotContain(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
    }

    /** Every membership read passes the provider's loader, so a failed invalidation is retried in the background. */
    @Test
    fun membershipCacheRetriesFailedInvalidations() {
        CoSecCacheSpecs.membership().policy.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.RETRY)
    }
}
