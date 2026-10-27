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

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.control.ControlCacheSpecs
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
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
        CoSecCacheSpecs.POLICY_CACHE_NAME to CoSecCacheSpecs.policy(codec(), policy = weakened).policy,
        CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME to CoSecCacheSpecs.globalPolicyIndex(policy = weakened).policy,
        CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME to CoSecCacheSpecs.appPermission(codec(), policy = weakened).policy,
        CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME to CoSecCacheSpecs.rolePermission(codec(), policy = weakened).policy,
        CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME to CoSecCacheSpecs.policySnapshot(policy = weakened).policy,
        // RFC 0007 §5.2: the control plane's records, indexes, back-references and leases.
        CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME to ControlCacheSpecs.revisionRecord(policy = weakened).policy,
        CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME to ControlCacheSpecs.revisionIndex(policy = weakened).policy,
        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME to ControlCacheSpecs.referenceIndex(policy = weakened).policy,
        CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME to ControlCacheSpecs.lease(policy = weakened).policy,
    )

    private inline fun <reified V : Any> codec() = CoSecCacheSpecs.jacksonCodec<V>(CoSecJsonSerializer)

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
        listOf(
            CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME,
            CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME,
            CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
            CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME,
        ).forEach { CoSecCacheSpecs.pinnedPolicy(it, weakened).ttl.assert().isNull() }
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

    @Test
    fun revokedTokenCacheBoundsMissingTtlAndKeepsDegradedReads() {
        val policy = CoSecCacheSpecs.revokedToken(codec()).policy
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
        policy.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
    }

    @Test
    fun revokedSubjectCacheIsConfiguredLikeRevokedTokens() {
        val spec = CoSecCacheSpecs.revokedSubject(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer))
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
