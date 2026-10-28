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

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.codec.jackson.JacksonCodec
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * CoSec's CoCache declarations.
 *
 * CoCache stores an entry under `{keyPrefix}{namespace}:{key}`; each cache sets an empty `keyPrefix` and a namespace
 * of [cacheNamespace] (`cosec:token:revoked`, `cosec:token:subject:revoked`, `cosec:snapshot` and so on). Each
 * namespace also names its own invalidation channel `cocache:inv:{namespace}`, so deployments with different key
 * prefixes stay apart.
 *
 * Snapshot and control-plane caches have no loader: Redis is the store of record. [pinnedPolicy] must therefore be the
 * last change to every cache's policy, after any customizer or configuration property.
 */
object CoSecCacheSpecs {
    const val REVOKED_TOKEN_CACHE_NAME = "RevokedTokenCache"

    /** The principals whose tokens were revoked all at once (RFC 0004 §4.5.3). */
    const val REVOKED_SUBJECT_CACHE_NAME = "RevokedSubjectCache"

    /** The memberships a `MembershipLoader` answers for principals whose token asks the provider (RFC 0004 §4.5.2). */
    const val MEMBERSHIP_CACHE_NAME = "MembershipCache"

    /**
     * How long a principal's cached memberships live: the longest a membership change can take to apply when its
     * invalidation is lost (RFC 0001 §3.2, the revocation delay of the provider source).
     */
    val DEFAULT_MEMBERSHIP_TTL: Duration = Duration.ofMinutes(1)

    /** The snapshot documents of RFC 0003 (§4.1): the store of record the snapshot source loads. */
    const val POLICY_SNAPSHOT_CACHE_NAME = "PolicySnapshotCache"

    /**
     * How long a local copy of a snapshot document lives (RFC 0003 §4.5, Q7). The copy serves cold loads and writers
     * only: installed snapshots are confirmed against Redis with conditional reads, so this does not bound their lag.
     */
    val DEFAULT_SNAPSHOT_LOCAL_MAX_AGE: Duration = Duration.ofSeconds(30)

    /** The control plane's revision records, revision indexes, back-references and leases (RFC 0007 §5.2). */
    const val REVISION_RECORD_CACHE_NAME = "RevisionRecordCache"
    const val REVISION_INDEX_CACHE_NAME = "RevisionIndexCache"
    const val REFERENCE_INDEX_CACHE_NAME = "ReferenceIndexCache"
    const val CONTROL_LEASE_CACHE_NAME = "ControlLeaseCache"

    /** What the control plane's periodic tasks visit: every indexed document and the changes that may be open. */
    const val CONTROL_LIST_CACHE_NAME = "ControlListCache"

    /** The control plane's change requests and each document's staging slot (RFC 0007 §5.2, §5.5). */
    const val CHANGE_REQUEST_CACHE_NAME = "ChangeRequestCache"
    const val STAGE_CACHE_NAME = "StageCache"

    /** Staged candidates (RFC 0007 §6.4): written by the control plane, read by every instance's shadow listener. */
    const val CANDIDATE_CACHE_NAME = "CandidateCache"

    /** What each instance saw of each shadow window, and each instance's installed revisions (RFC 0007 §6.4, §6.5). */
    const val SHADOW_REPORT_CACHE_NAME = "ShadowReportCache"
    const val INSTANCE_STATUS_CACHE_NAME = "InstanceStatusCache"

    /** The instances that reported on each shadow window (RFC 0007 §6.4, round-2 review R1). */
    const val SHADOW_REPORTER_CACHE_NAME = "ShadowReporterCache"

    /** The control plane's caches (RFC 0007 §5.2), created by `CoCacheControlStore.create`, not by the data plane. */
    val CONTROL_CACHE_NAMES: Set<String> = setOf(
        REVISION_RECORD_CACHE_NAME,
        REVISION_INDEX_CACHE_NAME,
        REFERENCE_INDEX_CACHE_NAME,
        CONTROL_LEASE_CACHE_NAME,
        CONTROL_LIST_CACHE_NAME,
        CHANGE_REQUEST_CACHE_NAME,
        STAGE_CACHE_NAME,
        CANDIDATE_CACHE_NAME,
    )

    /** Names of the caches whose only copy is Redis. */
    val STORE_OF_RECORD_CACHE_NAMES: Set<String> = setOf(
        POLICY_SNAPSHOT_CACHE_NAME,
        REVISION_RECORD_CACHE_NAME,
        REVISION_INDEX_CACHE_NAME,
        REFERENCE_INDEX_CACHE_NAME,
        CONTROL_LEASE_CACHE_NAME,
        CONTROL_LIST_CACHE_NAME,
        CHANGE_REQUEST_CACHE_NAME,
        STAGE_CACHE_NAME,
        CANDIDATE_CACHE_NAME,
    )

    private val NAMESPACES: Map<String, String> = mapOf(
        REVOKED_TOKEN_CACHE_NAME to "token:revoked",
        // Not under token:revoked, whose keys are token ids.
        REVOKED_SUBJECT_CACHE_NAME to "token:subject:revoked",
        POLICY_SNAPSHOT_CACHE_NAME to "snapshot",
        MEMBERSHIP_CACHE_NAME to "membership",
        REVISION_RECORD_CACHE_NAME to "control:revision",
        REVISION_INDEX_CACHE_NAME to "control:revisions",
        REFERENCE_INDEX_CACHE_NAME to "control:refs",
        CONTROL_LEASE_CACHE_NAME to "control:lease",
        CONTROL_LIST_CACHE_NAME to "control:list",
        CHANGE_REQUEST_CACHE_NAME to "control:change",
        STAGE_CACHE_NAME to "control:stage",
        CANDIDATE_CACHE_NAME to "candidate",
        SHADOW_REPORT_CACHE_NAME to "shadow:report",
        SHADOW_REPORTER_CACHE_NAME to "shadow:reporters",
        INSTANCE_STATUS_CACHE_NAME to "shadow:status",
    )

    /** The CoCache namespace of [cacheName]: `{keyPrefix}:policy` and so on. */
    fun cacheNamespace(keyPrefix: String, cacheName: String): String =
        "${keyPrefix.trimEnd(':')}:${NAMESPACES.getValue(cacheName)}"

    /**
     * Policy and permission caches are the store of record, so:
     *
     * - entries never expire (`ttl = null`); with an expiry, policies would silently disappear;
     * - content the cache cannot read, such as an unmigrated CoCache 5 value, fails the read
     *   ([ForeignFormatMode.FAIL]) and stays in Redis, instead of being reclaimed: with no loader, a reclaim would
     *   delete the only copy;
     * - a failed Redis read throws ([ReadFailureMode.THROW]) instead of degrading to a miss, which would silently
     *   drop a policy or permission. The security pipeline turns the error into a denied (500) response. (A
     *   `SetCache` such as the global policy index has nothing to degrade to and always rethrows; with CoCache 5 a
     *   degraded index read meant "no global policies", so explicit DENY rules were skipped.)
     */
    fun CachePolicy.storeOfRecord(): CachePolicy = toBuilder()
        .ttl(null)
        .readFailure(ReadFailureMode.THROW)
        .foreignFormat(ForeignFormatMode.FAIL)
        .build()

    /**
     * Revoked tokens are not the store of record (they expire with their tokens, and a failed read degrades on
     * purpose), but a 6.x revocation must not be reclaimed either: deleting it would accept the token again. Content
     * the cache cannot read therefore fails the read ([ForeignFormatMode.FAIL]), whatever `cocache.*` says.
     */
    fun CachePolicy.failOnForeignFormat(): CachePolicy = toBuilder()
        .foreignFormat(ForeignFormatMode.FAIL)
        .build()

    /**
     * The memberships cache is not the store of record, so its entries expire, but a failed read must never become
     * "no memberships" (RFC 0006 §5.5.2): with no roles, a DENY on a role would not apply. So:
     *
     * - entries always expire: a `ttl` of null becomes [DEFAULT_MEMBERSHIP_TTL], since a membership change whose
     *   invalidation is lost would otherwise never apply; without jitter, so the TTL is the bound it is documented as;
     * - a failed Redis read throws ([ReadFailureMode.THROW]) and content the cache cannot read fails the read
     *   ([ForeignFormatMode.FAIL]); the request fails closed with 503.
     */
    fun CachePolicy.expiringFailClosed(): CachePolicy = toBuilder()
        .ttl(ttl ?: DEFAULT_MEMBERSHIP_TTL)
        .ttlJitter(Duration.ZERO)
        .readFailure(ReadFailureMode.THROW)
        .foreignFormat(ForeignFormatMode.FAIL)
        .build()

    /**
     * What CoSec fixes in the policy of [cacheName], applied after any customizer or property:
     * [storeOfRecord] for policies and permissions, [failOnForeignFormat] for revoked tokens and subjects,
     * [expiringFailClosed] for memberships.
     */
    fun pinnedPolicy(cacheName: String, policy: CachePolicy): CachePolicy = when (cacheName) {
        in STORE_OF_RECORD_CACHE_NAMES -> policy.storeOfRecord()
        REVOKED_TOKEN_CACHE_NAME, REVOKED_SUBJECT_CACHE_NAME -> policy.failOnForeignFormat()
        MEMBERSHIP_CACHE_NAME -> policy.expiringFailClosed()
        else -> policy
    }

    /**
     * The snapshot documents (RFC 0003 §4.1): [storeOfRecord] like every policy cache, keys encoded explicitly
     * ([SnapshotKeyEncoder]), values by [SnapshotDocumentCodec]. Create it with
     * [me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache], never through an interface proxy (RFC 0003 §10).
     *
     * Redis must not evict these keys: run it with `maxmemory-policy noeviction`. Under an `allkeys-*` policy a
     * document can vanish. An instance that had read it fails closed (RFC 0003 §4.4 F2a), but an instance that starts
     * afterwards reads it as absent (F2): without the policies document there are no global policies, so a global DENY
     * no longer applies.
     */
    fun policySnapshot(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
        localMaxAge: Duration = DEFAULT_SNAPSHOT_LOCAL_MAX_AGE,
    ): CacheSpec<SnapshotKey, SnapshotDocument> =
        CacheSpec.builder<SnapshotKey, SnapshotDocument>(POLICY_SNAPSHOT_CACHE_NAME)
            .namespace(cacheNamespace(keyPrefix, POLICY_SNAPSHOT_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(SnapshotDocumentCodec)
            .policy(policy.toBuilder().localMaxAge(localMaxAge).build().storeOfRecord())
            .build()

    /**
     * [customized], the [policySnapshot] spec after cache customizers and `cocache.*` properties, with what the snapshot
     * cache keeps from [base] whatever they set (RFC 0008 §4.4): its storage layout, key encoder, codec, key filter and
     * entry lifetime, one local max age (`cosec.authorization.snapshot.local-max-age`), and [storeOfRecord]. A loader
     * is refused: Redis is the store of record, and only the snapshot source reads it.
     */
    fun pinPolicySnapshot(
        customized: CacheSpec<SnapshotKey, SnapshotDocument>,
        base: CacheSpec<SnapshotKey, SnapshotDocument>,
    ): CacheSpec<SnapshotKey, SnapshotDocument> {
        require(customized.loader == null && customized.asyncLoader == null) {
            "The ${base.name} cache takes no loader: Redis is the store of record, and only the snapshot source reads it."
        }
        val builder = customized.toBuilder()
            .namespace(base.namespace)
            .keyPrefix(base.keyPrefix)
            .keyEncoder(base.keyEncoder)
            .codec(base.codec)
            .policy(
                customized.policy.toBuilder()
                    .localMaxAge(base.policy.localMaxAge)
                    .degradedLocalMaxAge(base.policy.degradedLocalMaxAge)
                    .build()
                    .storeOfRecord(),
            )
        builder.keyFilter = base.keyFilter
        builder.ttlOf = base.ttlOf
        return builder.build()
    }

    /**
     * Revocations are always written with an explicit lifetime. The cache keeps CoCache's degraded read mode on
     * purpose: tokens are still signature-verified and expire on their own, and failing closed would reject every
     * authenticated request during a Redis outage. An unreadable revocation still fails ([failOnForeignFormat]).
     */
    fun revokedToken(
        codec: Codec<Boolean>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<String, Boolean> = CacheSpec.builder<String, Boolean>(REVOKED_TOKEN_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(codec)
        .policy(
            policy.toBuilder()
                .absentTtl(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
                .build()
                .failOnForeignFormat()
        )
        .build()

    /**
     * The revoked subjects, under the same rules as [revokedToken]: explicit lifetimes, CoCache's degraded read mode
     * (the documented exception to failing closed), and unreadable content fails the read. A key is a subject's
     * [RevokedSubjectCache.key]; a value is the revocation's `issuedBefore`, in epoch milliseconds.
     */
    fun revokedSubject(
        codec: Codec<Long>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<String, Long> = CacheSpec.builder<String, Long>(REVOKED_SUBJECT_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(codec)
        .policy(
            policy.toBuilder()
                .absentTtl(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
                .build()
                .failOnForeignFormat()
        )
        .build()

    /**
     * The memberships of principals whose token asks the provider ([CoCacheMembershipProvider]): keyed by
     * [MembershipCache.key], expiring after [ttl], and failing closed on read ([expiringFailClosed]).
     */
    fun membership(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
        ttl: Duration = DEFAULT_MEMBERSHIP_TTL,
    ): CacheSpec<String, Memberships> = CacheSpec.builder<String, Memberships>(MEMBERSHIP_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(MembershipsCodec)
        .policy(policy.toBuilder().ttl(ttl).build().expiringFailClosed())
        .build()

    inline fun <reified V : Any> jacksonCodec(mapper: ObjectMapper): Codec<V> =
        JacksonCodec(mapper, object : TypeReference<V>() {}.type)

    private fun <K : Any, V : Any> CacheSpec.Builder<K, V>.storageLayout(keyPrefix: String) = apply {
        namespace(cacheNamespace(keyPrefix, name))
        keyPrefix("")
        keyEncoder(KeyEncoder.ofToString())
    }
}
