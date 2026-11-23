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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.ControlCacheSpecs
import me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

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
        CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME to ControlCacheSpecs.lease(),
        CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME to ControlCacheSpecs.list(),
        // RC1-4: an app list read as absent would leave the grant analysis trusting nothing, never more; it still throws.
        CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME to ControlCacheSpecs.appList(),
        CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME to ControlCacheSpecs.changeRequest(),
        CoSecCacheSpecs.STAGE_CACHE_NAME to ControlCacheSpecs.stage(),
        // RFC 0007 §6.4: a staged candidate is the control plane's only copy while its change stages.
        CoSecCacheSpecs.CANDIDATE_CACHE_NAME to ShadowCacheSpecs.candidate(),
    )

    /** The sets of record: the back-references, the documents and the staged list (`CTL-42`, `CTL-44`, `CTL-46`). */
    private val setOfRecordSpecs: Map<String, SetStoreSpec<*, *>> = mapOf(
        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME to ControlCacheSpecs.referenceIndex(),
        CoSecCacheSpecs.CONTROL_DOCUMENT_SET_CACHE_NAME to ControlCacheSpecs.documentSet(),
        // CTL-54: the open-change sets, trusted once kept; a member lost to expiry would hide an open change.
        CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME to ControlCacheSpecs.changeSet(),
        // CTL-46: the staged list, which readers fall back from when its marker is gone: it never expires either.
        CoSecCacheSpecs.CANDIDATE_STAGED_CACHE_NAME to ControlCacheSpecs.stagedSet(),
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

        override fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>, locks: Locks): Layered<SetStoreSpec<K, E>> =
            CacheLayers.NONE.apply(
                spec.toBuilder().policy {
                    ttl = Duration.ofMinutes(1)
                    localMaxAge = Duration.ofHours(1)
                    degradedLocalMaxAge = Duration.ofHours(1)
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

    /**
     * `CTL-42`: a set of record never expires and keeps no local copy a read could answer from, degraded or not, whatever
     * a property or customizer says. A failed read always throws: a `SetStore` has no unknown-read to configure.
     */
    @Test
    fun setOfRecordCachesNeverExpireAndKeepNoLocalCopy() {
        setOfRecordSpecs.keys.assert().isEqualTo(SetsOfRecord.CACHE_NAMES)
        weakened { factory ->
            setOfRecordSpecs.forEach { (name, spec) ->
                factory.create(spec, CoSecCacheSpecs.locks(name)).assert().isInstanceOf(SetStore::class.java)
                val policy = factory.runtime.policy(name)!!
                policy.ttl.assert().describedAs(name).isNull()
                policy.localMaxAge.assert().describedAs(name).isEqualTo(SetsOfRecord.LOCAL_MAX_AGE)
                policy.degradedLocalMaxAge.assert().describedAs(name).isEqualTo(SetsOfRecord.LOCAL_MAX_AGE)
                CoSecCacheSpecs.locks(name).let { locks ->
                    listOf(
                        PolicyField.TTL,
                        PolicyField.LOCAL_MAX_AGE,
                        PolicyField.DEGRADED_LOCAL_MAX_AGE,
                    ).forEach { locks.isLocked(it).assert().describedAs("$name $it").isTrue() }
                }
            }
        }
    }

    @Test
    fun storeOfRecordCachesNeverExpireAndFailClosed() {
        (storeOfRecordSpecs.keys + setOfRecordSpecs.keys).assert().isEqualTo(
            CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES
        )
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
        ShadowCacheSpecs.statuses().policy.ttlJitter.assert().isEqualTo(Duration.ZERO)
        ShadowCacheSpecs.residents().path().assert().isEqualTo("cosec:shadow:resident")
        ShadowCacheSpecs.residents().policy.ttl.assert().isEqualTo(ShadowCacheSpecs.RESIDENT_TTL)
        ShadowCacheSpecs.residents().policy.ttlJitter.assert().isEqualTo(Duration.ZERO)
        weakened { factory ->
            listOf(
                ShadowCacheSpecs.reports(),
                ShadowCacheSpecs.reporters(),
                ShadowCacheSpecs.statuses(),
                ShadowCacheSpecs.residents(),
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
            // CTL-73, CTL-75: a status's and a resident record's key live exactly their life, whatever jitter a customizer
            // asks for (it asks a minute).
            for (spec in listOf(ShadowCacheSpecs.statuses(), ShadowCacheSpecs.residents())) {
                factory.runtime.policy(spec.name)!!.ttlJitter.assert().describedAs(spec.name).isEqualTo(Duration.ZERO)
                CoSecCacheSpecs.locks(spec.name).isLocked(PolicyField.TTL_JITTER).assert().isTrue()
            }
        }
    }

    /**
     * Review L2 of #1203: a read that times out is unavailable, which the pipeline's verification lets through (PIP-7),
     * so no layer may give the revocation tables a read timeout shorter than the store's command timeout.
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

    /**
     * Lengthens a store's "not found" answers, as `cocache.defaults.store.absent-ttl` or a customizer would, and its local
     * copies too when [localMaxAge] is set.
     */
    private class LongAbsence(private val localMaxAge: Duration? = null) : CacheLayers {
        override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
            CacheLayers.NONE.apply(
                spec.toBuilder().policy {
                    absentTtl = Duration.ofHours(1)
                    this@LongAbsence.localMaxAge?.let { localMaxAge = it }
                }.build(),
                locks,
            )

        override fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>, locks: Locks): Layered<SetStoreSpec<K, E>> =
            CacheLayers.NONE.apply(
                spec.toBuilder().policy {
                    ttl = Duration.ofMinutes(1)
                    localMaxAge = Duration.ofHours(1)
                    degradedLocalMaxAge = Duration.ofHours(1)
                }.build(),
                locks,
            )

        override fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
            CacheLayers.NONE.apply(spec, locks)
    }

    /**
     * #1629 finding 5: a revocation table keeps "not revoked" locally for 30 seconds at most, whatever a layer says. A
     * longer `absent-ttl` would accept a revoked token for that long after a lost invalidation.
     */
    @Test
    fun revocationTablesKeepNotRevokedFor30SecondsAtMost() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val factory = CoCacheFactory(runtime, LongAbsence())
            listOf(CoSecCacheSpecs.revokedToken(), CoSecCacheSpecs.revokedSubject()).forEach { spec ->
                CoSecCacheSpecs.locks(spec.name).isLocked(PolicyField.ABSENT_TTL).assert().isTrue()
                factory.create(spec, CoSecCacheSpecs.locks(spec.name))
                runtime.policy(spec.name)!!.absentTtl.assert().describedAs(spec.name)
                    .isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
            }
        }
    }

    /** DR1-D-3: a layer that keeps a revocation table's local copies longer than 30 seconds is refused. */
    @Test
    fun revocationTablesHoldARevocationFor30SecondsAtMost() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val factory = CoCacheFactory(runtime, LongAbsence(Duration.ofHours(1)))
            listOf(CoSecCacheSpecs.revokedToken(), CoSecCacheSpecs.revokedSubject()).forEach { spec ->
                CoSecCacheSpecs.locks(spec.name).isLocked(PolicyField.LOCAL_MAX_AGE).assert().isTrue()
                assertThrows<IllegalArgumentException> { factory.create(spec, CoSecCacheSpecs.locks(spec.name)) }
                    .message.assert().describedAs(spec.name).contains("local-max-age").contains("IDN-26")
            }
        }
    }

    @Test
    fun revocationTablesKeepTheirDeclaredCount() {
        listOf(CoSecCacheSpecs.revokedToken(), CoSecCacheSpecs.revokedSubject()).forEach { spec ->
            CoSecCacheSpecs.locks(
                spec.name
            ).isLocked(PolicyField.LOCAL_MAXIMUM_SIZE).assert().describedAs(spec.name).isTrue()
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
            val document = SnapshotDocuments.read(body.encodeToByteArray())
            cache.put(SnapshotKey.Platform, document)
            runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.clearLocal()
            cache.get(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
            runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.inspect("platform").remote.ttl.assert().isNull()
        }
    }

    @Test
    fun revokedTokenCacheBoundsMissingTtlAndReadsUnknownWhenRedisFails() {
        val spec = CoSecCacheSpecs.revokedToken()
        spec.policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.THROW)
    }

    /**
     * Round-2 review H1: a failed read is unknown in both tables, and no layer can make it absent, which would read "not
     * revoked" to refresh as well as to the pipeline.
     */
    @Test
    fun revokedSubjectCacheIsConfiguredLikeRevokedTokens() {
        val spec = CoSecCacheSpecs.revokedSubject()
        spec.policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.THROW)
        (spec.keyPrefix + spec.namespace).assert().isEqualTo("cosec:token:subject:revoked")
        listOf(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME).forEach { name ->
            CoSecCacheSpecs.locks(name).isLocked(PolicyField.UNKNOWN_READ).assert().isTrue()
            CoSecCacheSpecs.locks(name).isLocked(LayoutField.KEY_PREFIX).assert().isTrue()
        }
        weakened { factory ->
            listOf(CoSecCacheSpecs.revokedToken(), spec).forEach {
                factory.create(it, CoSecCacheSpecs.locks(it.name))
                factory.effective(it.name, PolicyField.UNKNOWN_READ).assert().describedAs(it.name).isEqualTo("throw")
            }
        }
    }
}
