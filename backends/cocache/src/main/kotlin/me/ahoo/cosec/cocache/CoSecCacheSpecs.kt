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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.codec.jackson.JacksonCodec
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.Ttl
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.TokenFamily
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * CoSec's CoCache declarations.
 *
 * CoCache stores an entry under `{keyPrefix}{namespace}:{key}`, and a namespace may hold no `:`. Each CoSec cache has a
 * storage path under CoSec's key prefix ([storagePath]: `cosec:token:revoked`, `cosec:snapshot`,
 * `cosec:control:revision` and so on), split at its last `:` into CoCache's key prefix and namespace ([layout]):
 * `cosec:token:` and `revoked`, `cosec:` and `snapshot`. The storage keys are the storage path, `:` and the encoded key,
 * whatever the split; the invalidation channel of each cache is `{keyPrefix}inv:{namespace}`.
 *
 * Every CoSec cache is created through `CoCacheFactory` with [locks]: the application's `cocache.*` properties and
 * customizers apply to it, except for what CoSec's correctness depends on, which they can neither change nor weaken.
 * Policies, permissions and the control plane's records are [StoreSpec]s: Redis is their store of record.
 */
@InternalCoSecApi
object CoSecCacheSpecs {
    /** Who locks CoSec's caches, as `CoCacheFactory`'s start log, endpoint and errors name it. */
    const val LOCKED_BY = "cosec"

    const val REVOKED_TOKEN_CACHE_NAME = "RevokedTokenCache"

    /** The principals whose tokens were revoked all at once (RFC 0004 §4.5.3). */
    const val REVOKED_SUBJECT_CACHE_NAME = "RevokedSubjectCache"

    /** The refresh token families, one per live session (RFC 0009): read and written on refresh and logout only. */
    const val TOKEN_FAMILY_CACHE_NAME = "TokenFamilyCache"

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

    /** The app documents there are, which the grant analysis reads on every tenant change (RC1-4). */
    const val CONTROL_APP_LIST_CACHE_NAME = "ControlAppListCache"

    /** The control plane's change requests and each document's staging slot (RFC 0007 §5.2, §5.5). */
    const val CHANGE_REQUEST_CACHE_NAME = "ChangeRequestCache"
    const val STAGE_CACHE_NAME = "StageCache"

    /** Staged candidates (RFC 0007 §6.4): written by the control plane, read by every instance's shadow listener. */
    const val CANDIDATE_CACHE_NAME = "CandidateCache"

    /** What each instance saw of each shadow window, and each instance's installed revisions (RFC 0007 §6.4, §6.5). */
    const val SHADOW_REPORT_CACHE_NAME = "ShadowReportCache"
    const val INSTANCE_STATUS_CACHE_NAME = "InstanceStatusCache"

    /** Which instances have a status to read: a short list, changed when an instance joins or leaves (#1214). */
    const val INSTANCE_INDEX_CACHE_NAME = "InstanceIndexCache"

    /** The instances that reported on each shadow window (RFC 0007 §6.4, round-2 review R1). */
    const val SHADOW_REPORTER_CACHE_NAME = "ShadowReporterCache"

    /** The control plane's caches (RFC 0007 §5.2), created by `CoCacheControlStore.create`, not by the data plane. */
    val CONTROL_CACHE_NAMES: Set<String> = setOf(
        REVISION_RECORD_CACHE_NAME,
        REVISION_INDEX_CACHE_NAME,
        REFERENCE_INDEX_CACHE_NAME,
        CONTROL_LEASE_CACHE_NAME,
        CONTROL_LIST_CACHE_NAME,
        CONTROL_APP_LIST_CACHE_NAME,
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
        CONTROL_APP_LIST_CACHE_NAME,
        CHANGE_REQUEST_CACHE_NAME,
        STAGE_CACHE_NAME,
        CANDIDATE_CACHE_NAME,
    )

    /** The shadow window's observations: they expire, but a failed read still fails (RFC 0007 §6.4, §6.5). */
    val SHADOW_CACHE_NAMES: Set<String> = setOf(
        SHADOW_REPORT_CACHE_NAME,
        SHADOW_REPORTER_CACHE_NAME,
        INSTANCE_STATUS_CACHE_NAME,
        INSTANCE_INDEX_CACHE_NAME,
    )

    private val PATHS: Map<String, String> = mapOf(
        REVOKED_TOKEN_CACHE_NAME to "token:revoked",
        // Not under token:revoked, whose keys are token ids.
        REVOKED_SUBJECT_CACHE_NAME to "token:subject:revoked",
        TOKEN_FAMILY_CACHE_NAME to "token:family",
        POLICY_SNAPSHOT_CACHE_NAME to "snapshot",
        MEMBERSHIP_CACHE_NAME to "membership",
        REVISION_RECORD_CACHE_NAME to "control:revision",
        REVISION_INDEX_CACHE_NAME to "control:revisions",
        REFERENCE_INDEX_CACHE_NAME to "control:refs",
        CONTROL_LEASE_CACHE_NAME to "control:lease",
        CONTROL_LIST_CACHE_NAME to "control:list",
        CONTROL_APP_LIST_CACHE_NAME to "control:apps",
        CHANGE_REQUEST_CACHE_NAME to "control:change",
        STAGE_CACHE_NAME to "control:stage",
        CANDIDATE_CACHE_NAME to "candidate",
        SHADOW_REPORT_CACHE_NAME to "shadow:report",
        SHADOW_REPORTER_CACHE_NAME to "shadow:reporters",
        INSTANCE_STATUS_CACHE_NAME to "shadow:status",
        INSTANCE_INDEX_CACHE_NAME to "shadow:instances",
    )

    /**
     * Every cache CoSec may create, by name. An instance creates those of the features it runs: the control plane's
     * caches only where the control plane runs, [MEMBERSHIP_CACHE_NAME] only with a membership loader, the revocation
     * tables only with JWT.
     */
    val CACHE_NAMES: Set<String> = PATHS.keys

    /**
     * A read timeout CoCache keeps as the store's command timeout: it stores any value of 36,500 days or more as none
     * (`PolicyField.READ_TIMEOUT`).
     */
    private val COMMAND_TIMEOUT: Duration = ChronoUnit.FOREVER.duration

    /** Where a cache keeps its entries: `{keyPrefix}{namespace}:{encodedKey}`, in CoCache's terms. */
    @InternalCoSecApi
    data class Layout(val keyPrefix: String, val namespace: String) {
        /** `{keyPrefix}{namespace}`, the part of every storage key before `:` and the encoded key. */
        val storagePath: String get() = keyPrefix + namespace
    }

    /** The storage path of [cacheName] under CoSec's [keyPrefix]: `{keyPrefix}:snapshot` and so on. */
    fun storagePath(keyPrefix: String, cacheName: String): String =
        "${keyPrefix.trimEnd(':')}:${PATHS.getValue(cacheName)}"

    /**
     * Where a deployment keeps its API keys: `<keyPrefix>:api-key`. Every key a deployment writes lives under its one
     * prefix (`SNP-9`, RC1-46), so two deployments on one Redis that differ in it share nothing.
     */
    fun apiKeyPrefix(keyPrefix: String): String = "${keyPrefix.trimEnd(':')}:$API_KEY_FAMILY"

    /** Where a deployment keeps its rate limit counters: `<keyPrefix>:rate-limiter` (`SNP-9`). */
    fun rateLimiterPrefix(keyPrefix: String): String = "${keyPrefix.trimEnd(':')}:$RATE_LIMITER_FAMILY"

    private const val API_KEY_FAMILY = "api-key"
    private const val RATE_LIMITER_FAMILY = "rate-limiter"

    /**
     * [storagePath] split at its last `:`: CoCache's key prefix (`cosec:control:`) and namespace (`revision`), which may
     * hold no `:`. The storage keys stay `{storagePath}:{encodedKey}`.
     */
    fun layout(keyPrefix: String, cacheName: String): Layout {
        val path = storagePath(keyPrefix, cacheName)
        val split = path.lastIndexOf(':')
        return Layout(path.substring(0, split + 1), path.substring(split + 1))
    }

    /**
     * What CoSec fixes in each of its caches, whatever the `cocache.*` properties and customizers say (CoCache's
     * `lock` and `constrain`, applied last). Every cache keeps CoSec's storage layout, codec and data source: its keys
     * stay where every instance and the control plane look for them, and a cache without a source never gets one.
     *
     * Policies, permissions and the control plane's records are the store of record ([STORE_OF_RECORD_CACHE_NAMES]):
     *
     * - they are `Store`s: a failed delete throws instead of being retried later (a late retry would delete what another
     *   writer wrote in between, such as a revision record written again or a lease taken again), and content the
     *   cache cannot read fails the read and stays in Redis;
     * - entries never expire (`ttl` locked and constrained to forever): with an expiry, policies would silently
     *   disappear;
     * - a failed Redis read is `Lookup.Unknown` and `get` throws (`unknown-read` locked to `throw`), instead of
     *   degrading to a miss, which would silently drop a policy or permission. The security pipeline turns the error
     *   into a denied (503) response.
     *
     * The memberships cache is not the store of record, so its entries expire, but a failed read must never become "no
     * memberships" (RFC 0006 §5.5.2): with no roles, a DENY on a role would not apply. So its `ttl` is always finite
     * ([DEFAULT_MEMBERSHIP_TTL] when the effective value is forever) and without jitter, so the TTL is the bound it is
     * documented as, and a failed Redis read throws (`read-failure` and `unknown-read` locked to `throw`): the request
     * fails closed with 503.
     *
     * The revocation tables read a failed Redis read as unknown (`unknown-read` locked to `throw`), which their reader
     * reports as unavailable: never "not revoked", which would let refresh issue a token on a read that failed (round-2
     * review H1). What an unavailable store means is the caller's: only the pipeline's verification lets a token through
     * on it (PIP-7). Their read timeout is the store's command timeout, which no layer shortens: a shorter one would make
     * more reads fail. The shadow caches fail a failed read, and keep their declared lifetimes.
     *
     * A constraint runs after `cocache.caches.[name].unlock` too: breaking the glass on these fields changes nothing.
     */
    fun locks(cacheName: String): Locks = Locks.builder(LOCKED_BY).apply {
        lockLayout(*LayoutField.entries.toTypedArray())
        when (cacheName) {
            in STORE_OF_RECORD_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is the store of record: a Store." } }
                lock(PolicyField.TTL, PolicyField.UNKNOWN_READ)
                constrain(PolicyField.TTL) { Ttl.Forever }
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                if (cacheName == POLICY_SNAPSHOT_CACHE_NAME) {
                    // One local max age, cosec.authorization.snapshot.local-max-age (RFC 0008 §4.4).
                    lock(PolicyField.LOCAL_MAX_AGE)
                }
            }

            in SHADOW_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                lock(PolicyField.UNKNOWN_READ, PolicyField.TTL)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // The reports outlive any window, and a status counts for a minute (RFC 0007 §6.5): fixed, as on main.
                val ttl = if (cacheName == INSTANCE_STATUS_CACHE_NAME) ShadowCacheSpecs.STATUS_TTL else ShadowCacheSpecs.REPORT_TTL
                constrain(PolicyField.TTL) { Ttl.of(ttl) }
            }

            REVOKED_TOKEN_CACHE_NAME, REVOKED_SUBJECT_CACHE_NAME -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                // A failed read is unknown, never absent: absent would read "not revoked" to refresh too (H1).
                lock(PolicyField.UNKNOWN_READ, PolicyField.READ_TIMEOUT)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // A read that times out is unavailable, which the pipeline lets through (PIP-7): a timeout shorter than
                // the store's command timeout, from cocache.defaults.store.read-timeout say, would widen that.
                constrain(PolicyField.READ_TIMEOUT) { COMMAND_TIMEOUT }
            }

            TOKEN_FAMILY_CACHE_NAME -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                // A family that cannot be read is no answer, never "no family" (RFC 0009): refresh and logout fail.
                lock(PolicyField.UNKNOWN_READ)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
            }

            MEMBERSHIP_CACHE_NAME -> {
                verify {
                    require(it is CacheSpec<*, *> && it.externallySourced) {
                        "CoSec's ${it.name} is an externally sourced Cache."
                    }
                }
                lock(PolicyField.READ_FAILURE, PolicyField.UNKNOWN_READ)
                constrain(PolicyField.TTL) { it.finiteOr(DEFAULT_MEMBERSHIP_TTL) }
                constrain(PolicyField.TTL_JITTER) { Duration.ZERO }
                constrain(PolicyField.READ_FAILURE) { ReadFailureMode.THROW }
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
            }

            else -> throw IllegalArgumentException("[$cacheName] is not one of CoSec's caches.")
        }
    }.build()

    /**
     * The snapshot documents (RFC 0003 §4.1): the store of record like every policy cache ([locks]), keys encoded
     * explicitly ([SnapshotKeyEncoder]), values by [SnapshotDocumentCodec], one local max age. Create it with
     * [me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache], never through an interface proxy (RFC 0003 §10).
     *
     * Redis must not evict these keys: run it with `maxmemory-policy noeviction`. Under an `allkeys-*` policy a
     * document can vanish. An instance that had read it fails closed (RFC 0003 §4.4 F2a). An instance that reads it
     * afterwards, a new one or one that evicted its copy, reads it as absent (F2): an absent platform document still
     * fails every request closed (RFC 0006's `platformRequired`), but an absent app or tenant document is an empty one,
     * so its DENY statements silently stop applying (RC1-47). The starter checks the policy at startup (`SNP-3`) and
     * reads it again for its health indicator; CoCache refuses to start with an `allkeys-*` policy as well.
     */
    fun policySnapshot(
        keyPrefix: String = CoSec.COSEC,
        localMaxAge: Duration = DEFAULT_SNAPSHOT_LOCAL_MAX_AGE,
    ): StoreSpec<SnapshotKey, SnapshotDocument> =
        storeOfRecord(POLICY_SNAPSHOT_CACHE_NAME, keyPrefix, SnapshotKeyEncoder, SnapshotDocumentCodec)
            .policy { this.localMaxAge = localMaxAge }
            .build()

    /**
     * A store of record under CoSec's layout, with nothing but the type's defaults (`ttl` forever, a failed read
     * `Unknown`); [locks] keeps it so.
     */
    fun <K : Any, V : Any> storeOfRecord(
        cacheName: String,
        keyPrefix: String,
        keyEncoder: KeyEncoder<K>,
        codec: Codec<V>,
    ): StoreSpec.Builder<K, V> {
        require(cacheName in STORE_OF_RECORD_CACHE_NAMES) { "[$cacheName] is not a store of record." }
        val layout = layout(keyPrefix, cacheName)
        return StoreSpec.builder<K, V>(cacheName)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(keyEncoder)
            .codec(codec)
    }

    /**
     * What both revocation tables declare, before `cocache.*` properties and customizers:
     *
     * - they are `Store`s, written with an explicit lifetime each ([CoCacheTokenStore]); content the table cannot read
     *   fails the read, so a revocation the table cannot read is never reclaimed (deleting it would accept the token
     *   again);
     * - "not revoked" is kept locally for [RevokedTokenCache.MISSING_TTL_SECONDS];
     * - a failed Redis read is unknown (`unknownRead = THROW`, locked): [CoCacheRevocationReader] reports it as
     *   unavailable, and only the pipeline's verification lets a token through on it (PIP-7), never refresh (`IDN-14`).
     *   Such a read caches nothing: every read during the outage asks Redis again, and a revocation applies as soon as
     *   Redis answers.
     */
    private fun <V : Any> revocation(cacheName: String, keyPrefix: String, codec: Codec<V>): StoreSpec<String, V> {
        val layout = layout(keyPrefix, cacheName)
        return StoreSpec.builder<String, V>(cacheName)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder.ofToString())
            .codec(codec)
            .unknownRead(UnknownReadMode.THROW)
            .policy { absentTtl = Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS) }
            .build()
    }

    /**
     * The revoked token ids ([RevokedTokenCache]), under the rules of [revocation], read with [CoSecJson.mapper], CoSec's
     * one mapper, not the application's (RC1-44).
     */
    fun revokedToken(keyPrefix: String = CoSec.COSEC): StoreSpec<String, Boolean> =
        revocation(REVOKED_TOKEN_CACHE_NAME, keyPrefix, jacksonCodec<Boolean>(CoSecJson.mapper))

    /**
     * The revoked subjects, under the same rules as [revokedToken]. A key is a subject's [RevokedSubjectCache.key]; a
     * value is the revocation's `issuedBefore`, in epoch milliseconds.
     */
    fun revokedSubject(keyPrefix: String = CoSec.COSEC): StoreSpec<String, Long> =
        revocation(REVOKED_SUBJECT_CACHE_NAME, keyPrefix, jacksonCodec<Long>(CoSecJson.mapper))

    /**
     * The refresh token families ([CoCacheTokenFamilyStore]): a `Store` read by conditional reads of Redis only, whose
     * entries each carry the family's remaining lifetime, so a family leaves Redis at its end; a failed read throws
     * ([locks]).
     */
    fun tokenFamily(keyPrefix: String = CoSec.COSEC): StoreSpec<String, TokenFamily> {
        val layout = layout(keyPrefix, TOKEN_FAMILY_CACHE_NAME)
        return StoreSpec.builder<String, TokenFamily>(TOKEN_FAMILY_CACHE_NAME)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder.ofToString())
            .codec(TokenFamilyCodec)
            .unknownRead(UnknownReadMode.THROW)
            .build()
    }

    /**
     * The memberships of principals whose token asks the provider ([CoCacheMembershipProvider]): an externally sourced
     * `Cache`, which the provider reads with its own loader on each call, keyed by [MembershipCache.key], expiring
     * after [ttl], and failing closed on read ([locks]).
     *
     * A failed [CoCacheMembershipProvider.invalidate] is retried in the background ([InvalidateFailureMode.RETRY]):
     * every read passes the provider's loader, so a late delete only makes the next request load again.
     */
    fun membership(
        keyPrefix: String = CoSec.COSEC,
        ttl: Duration = DEFAULT_MEMBERSHIP_TTL
    ): CacheSpec<String, Memberships> {
        val layout = layout(keyPrefix, MEMBERSHIP_CACHE_NAME)
        return CacheSpec.builder<String, Memberships>(MEMBERSHIP_CACHE_NAME)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder.ofToString())
            .codec(MembershipsCodec)
            .externallySourced()
            .policy {
                this.ttl = ttl
                ttlJitter = Duration.ZERO
                readFailure = ReadFailureMode.THROW
                invalidateFailure = InvalidateFailureMode.RETRY
            }
            .build()
    }

    internal inline fun <reified V : Any> jacksonCodec(mapper: ObjectMapper): Codec<V> =
        JacksonCodec(mapper, object : TypeReference<V>() {}.type)
}

/** Creates [spec] through this factory with CoSec's locks ([CoSecCacheSpecs.locks]). */
@InternalCoSecApi
fun <K : Any, V : Any> CoCacheFactory.createLocked(spec: StoreSpec<K, V>): Store<K, V> =
    create(spec, CoSecCacheSpecs.locks(spec.name))
