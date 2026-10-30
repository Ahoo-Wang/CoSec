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

import io.mockk.mockk
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.control.ControlCacheSpecs
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Policy and permission caches are the store of record in Redis (no loader), so they must never expire and a failed
 * Redis read, or a read of unmigrated content, must throw. Guards against CoCache's defaults (`ttl` 1 hour, degraded reads) silently applying to them.
 */
class CacheTtlPolicyTest {
    /** A policy that expires and degrades, as CoCache's defaults or a `cocache.defaults.*` property would. */
    private val weakened = CachePolicy.builder()
        .ttl(Duration.ofMinutes(1))
        .readFailure(ReadFailureMode.DEGRADE)
        .foreignFormat(ForeignFormatMode.RECLAIM)
        .build()

    private val storeOfRecordPolicies: Map<String, CachePolicy> = mapOf(
        CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME to CoSecCacheSpecs.policySnapshot(policy = weakened).policy,
        // RFC 0007 §5.2: the control plane's records, indexes, back-references and leases.
        CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME to ControlCacheSpecs.revisionRecord(policy = weakened).policy,
        CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME to ControlCacheSpecs.revisionIndex(policy = weakened).policy,
        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME to ControlCacheSpecs.referenceIndex(policy = weakened).policy,
        CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME to ControlCacheSpecs.lease(policy = weakened).policy,
        CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME to ControlCacheSpecs.list(policy = weakened).policy,
        // RC1-4: an app list read as absent would leave the grant analysis trusting nothing, never more; it still throws.
        CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME to ControlCacheSpecs.appList(policy = weakened).policy,
        CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME to ControlCacheSpecs.changeRequest(policy = weakened).policy,
        CoSecCacheSpecs.STAGE_CACHE_NAME to ControlCacheSpecs.stage(policy = weakened).policy,
        // RFC 0007 §6.4: a staged candidate is the control plane's only copy while its change stages.
        CoSecCacheSpecs.CANDIDATE_CACHE_NAME to me.ahoo.cosec.cache.shadow.ShadowCacheSpecs.candidate(policy = weakened).policy,
    )

    private inline fun <reified V : Any> codec() = CoSecCacheSpecs.jacksonCodec<V>(CoSecJson.mapper)

    @Test
    fun storeOfRecordCachesNeverExpireAndFailClosed() {
        storeOfRecordPolicies.keys.assert().isEqualTo(CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES)
        storeOfRecordPolicies.values.forEach { policy ->
            policy.ttl.assert().isNull()
            policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
            policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        }
    }

    @Test
    fun controlCachesLiveUnderTheirOwnNamespaces() {
        ControlCacheSpecs.revisionRecord().namespace.assert().isEqualTo("cosec:control:revision")
        ControlCacheSpecs.revisionIndex("other:").namespace.assert().isEqualTo("other:control:revisions")
        ControlCacheSpecs.referenceIndex().namespace.assert().isEqualTo("cosec:control:refs")
        ControlCacheSpecs.lease().namespace.assert().isEqualTo("cosec:control:lease")
        ControlCacheSpecs.list().namespace.assert().isEqualTo("cosec:control:list")
        ControlCacheSpecs.appList().namespace.assert().isEqualTo("cosec:control:apps")
        ControlCacheSpecs.changeRequest().namespace.assert().isEqualTo("cosec:control:change")
        ControlCacheSpecs.stage().namespace.assert().isEqualTo("cosec:control:stage")
        listOf(
            CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME,
            CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME,
            CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
            CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME,
            CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME,
            CoSecCacheSpecs.STAGE_CACHE_NAME,
        ).forEach { CoSecCacheSpecs.pinnedPolicy(it, weakened).ttl.assert().isNull() }
    }

    /** RFC 0007 §6.4, §6.5: candidates are kept like policies; reports and statuses expire, but never read a failure as empty. */
    @Test
    fun shadowCachesLiveUnderTheirNamespacesAndFailClosed() {
        val shadow = me.ahoo.cosec.cache.shadow.ShadowCacheSpecs
        shadow.candidate().namespace.assert().isEqualTo("cosec:candidate")
        shadow.reports().namespace.assert().isEqualTo("cosec:shadow:report")
        shadow.statuses().namespace.assert().isEqualTo("cosec:shadow:status")
        shadow.reporters().namespace.assert().isEqualTo("cosec:shadow:reporters")
        shadow.reporters(policy = weakened).policy.ttl.assert().isEqualTo(shadow.REPORT_TTL)
        shadow.reporters(policy = weakened).policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        shadow.reports(policy = weakened).policy.ttl.assert().isEqualTo(shadow.REPORT_TTL)
        shadow.statuses(policy = weakened).policy.ttl.assert().isEqualTo(shadow.STATUS_TTL)
        shadow.reports(policy = weakened).policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        shadow.statuses(policy = weakened).policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
    }

    @Test
    fun storeOfRecordOverridesWhateverCameBefore() {
        val policy = weakened.storeOfRecord()
        policy.ttl.assert().isNull()
        policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
    }

    /** Review requirement: the snapshot cache pins `ttl = null` and `ForeignFormatMode.FAIL` like the others. */
    @Test
    fun snapshotCacheIsStoreOfRecordAndKeepsItsLocalCopiesShort() {
        val spec = CoSecCacheSpecs.policySnapshot(policy = weakened)
        spec.policy.ttl.assert().isNull()
        spec.policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        spec.policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        spec.policy.localMaxAge.assert().isEqualTo(CoSecCacheSpecs.DEFAULT_SNAPSHOT_LOCAL_MAX_AGE)
        spec.namespace.assert().isEqualTo("cosec:snapshot")
        spec.keyPrefix.assert().isEmpty()
        CoSecCacheSpecs.policySnapshot("other:", localMaxAge = Duration.ofSeconds(5)).let {
            it.namespace.assert().isEqualTo("other:snapshot")
            it.policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        }
        CoSecCacheSpecs.pinnedPolicy(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, weakened).ttl.assert().isNull()
    }

    /** RFC 0008 §4.4: customizers and `cocache.*` properties cannot move what the snapshot cache pins. */
    @Test
    fun snapshotCacheKeepsItsPinsWhateverCustomizersSet() {
        val base = CoSecCacheSpecs.policySnapshot("pinned:", localMaxAge = Duration.ofSeconds(7))
        val customized = base.toBuilder()
            .keyPrefix("elsewhere:")
            .policy(weakened.toBuilder().localMaxAge(Duration.ofHours(1)).build())
            .build()
        val pinned = CoSecCacheSpecs.pinPolicySnapshot(customized, base)
        pinned.keyPrefix.assert().isEmpty()
        pinned.namespace.assert().isEqualTo("pinned:snapshot")
        pinned.codec.assert().isSameAs(base.codec)
        pinned.keyEncoder.assert().isSameAs(base.keyEncoder)
        pinned.policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(7))
        pinned.policy.ttl.assert().isNull()
        pinned.policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        val loading = base.toBuilder().loader(mockk<CacheLoader<SnapshotKey, SnapshotDocument>>()).build()
        assertThrows<IllegalArgumentException> { CoSecCacheSpecs.pinPolicySnapshot(loading, base) }
    }

    @Test
    fun revokedTokenCacheBoundsMissingTtlAndKeepsDegradedReads() {
        val policy = CoSecCacheSpecs.revokedToken(codec()).policy
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        policy.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
    }

    @Test
    fun revokedSubjectCacheIsConfiguredLikeRevokedTokens() {
        val spec = CoSecCacheSpecs.revokedSubject(CoSecCacheSpecs.jacksonCodec(CoSecJson.mapper))
        spec.policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        spec.policy.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
        spec.policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        spec.namespace.assert().isEqualTo("cosec:token:subject:revoked")
        val weakened = spec.policy.toBuilder().foreignFormat(ForeignFormatMode.RECLAIM).build()
        CoSecCacheSpecs.pinnedPolicy(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, weakened).foreignFormat
            .assert().isEqualTo(ForeignFormatMode.FAIL)
        CoSecCacheSpecs.pinnedPolicy(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, weakened).foreignFormat
            .assert().isEqualTo(ForeignFormatMode.FAIL)
    }

    /**
     * RFC 0006 §5.5.2: the memberships cache expires by design, but never degrades a failed read into "no memberships",
     * and it can never be made to live forever: a lost invalidation would then never apply.
     */
    @Test
    fun membershipCacheExpiresButFailsClosed() {
        val spec = CoSecCacheSpecs.membership(policy = weakened, ttl = Duration.ofSeconds(5))
        spec.policy.ttl.assert().isEqualTo(Duration.ofSeconds(5))
        spec.policy.ttlJitter.assert().isEqualTo(Duration.ZERO)
        spec.policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        spec.policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        spec.namespace.assert().isEqualTo("cosec:membership")

        val forever = weakened.toBuilder().ttl(null).build()
        CoSecCacheSpecs.pinnedPolicy(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, forever).let {
            it.ttl.assert().isEqualTo(CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL)
            it.ttlJitter.assert().isEqualTo(Duration.ZERO)
            it.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
            it.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        }
        CoSecCacheSpecs.pinnedPolicy(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, weakened).ttl
            .assert().isEqualTo(Duration.ofMinutes(1))
        CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES.assert().doesNotContain(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
    }
}
