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

import io.mockk.mockk
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.control.ControlSets
import me.ahoo.cosec.cocache.control.ListedCodec
import me.ahoo.cosec.cocache.control.OpenChangeSets
import me.ahoo.cosec.cocache.control.ReferenceKey
import me.ahoo.cosec.cocache.control.ReferenceKeyEncoder
import me.ahoo.cosec.cocache.control.RevisionRecordKey
import me.ahoo.cosec.cocache.control.RevisionRecordKeyEncoder
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.cocache.snapshot.rawStore
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.store.ReclaimLease
import me.ahoo.cosec.engine.CompiledRateLimit
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant

/**
 * The golden samples of every [FrozenLayout] entry that is a key, a bucket or a channel (RFC 0014 R6, `SNP-26`): fixed
 * inputs to the outputs 8.x keeps, taken from the code that derives them (CoCache's own storage key and channel for a
 * cache's). What a script stores is held by `FrozenScriptsTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrozenLayoutTest {
    /** The default prefix, and one with a hash tag around it (`SNP-9`): the prefix is joined the same way to either. */
    private val caches = listOf("cosec", "{cosec}").associateWith { CoSecTestCaches(keyPrefix = it) }

    @AfterAll
    fun close() {
        caches.values.forEach(CoSecTestCaches::close)
        revocationCaches.close()
        rawRuntime.close()
    }

    private fun admin(prefix: String, cacheName: String) = checkNotNull(
        caches.getValue(prefix).runtime.admin(cacheName)
    )

    private fun storageKey(prefix: String, cacheName: String, encodedKey: String): String =
        admin(prefix, cacheName).inspect(encodedKey).storageKey

    private fun channel(prefix: String, cacheName: String): String = channelOf(admin(prefix, cacheName).keyspace)

    /**
     * The token store and its reader as an instance runs them, and a second runtime over the same backend that reads and
     * writes the bytes of their values, as another instance would.
     */
    private val revocations = InMemoryBackend()
    private val revocationCaches = CoSecTestCaches(revocations, keyPrefix = "cosec")
    private val tokens by lazy {
        CoCacheTokenStore(revocationCaches.revokedTokenCache, revocationCaches.revokedSubjectCache)
    }
    private val rawRuntime = CacheRuntime.builder().backend(revocations).build()
    private val raws = HashMap<String, Store<String, ByteArray>>()

    private fun raw(cacheName: String) = raws.getOrPut(cacheName) {
        rawRuntime.create(rawStore("Raw-$cacheName", "cosec", cacheName, KeyEncoder<String> { it }))
    }

    private fun rawRead(cacheName: String, key: String): String = runBlocking {
        when (val read = raw(cacheName).async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value.decodeToString()
            else -> "absent"
        }
    }

    private fun rawWrite(cacheName: String, key: String, value: String) = runBlocking {
        raw(cacheName).async().put(key, value.encodeToByteArray()).await()
    }

    /**
     * The Redis channel of a keyspace, `{keyPrefix}inv:{namespace}`: CoCache's naming (its `RedisInvalidationChannel`,
     * internal there, and the `ExternalInvalidator` contract). CoSec freezes the keyspace; a CoCache that named channels
     * otherwise would break every CoCache deployment across its versions, not only CoSec's (RFC 0014 §4).
     */
    private fun channelOf(keyspace: Keyspace): String = keyspace.keyPrefix + "inv:" + keyspace.namespace

    init {
        caches.forEach { (prefix, each) ->
            each.revokedTokenCache
            each.revokedSubjectCache
            each.tokenFamilyCache
            // The control plane's caches, the candidates' and the documents' among them, as a control host creates them.
            CoCacheControlStore.create(
                each.factory,
                each.factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(prefix)),
                prefix
            )
            CoCacheShadowReports.create(each.factory, prefix)
        }
    }

    private fun bucket(value: String) = CompiledRateLimit.keyedBucket(value)

    private val limiter = ReactiveRedisRateLimiter(mockk(relaxed = true), CoSecCacheSpecs.rateLimiterPrefix("cosec"))

    private val goldens: List<Golden> = listOf(
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "a plain key value", "k:alice") { bucket("alice") },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "a value of 256 characters is written whole", "k:" + "x".repeat(256)) {
            bucket("x".repeat(256))
        },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "a value of 257 characters is hashed",
            "h:4b9a9ea9f505aa646f36a29a07fbbe5a854128f516f38196324bf804ea7bb01b",
        ) { bucket("x".repeat(257)) },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "an unpaired surrogate and a backslash are escaped",
            "e:a\\uD800b\\\\"
        ) {
            bucket("a\uD800b\\")
        },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "an escaped value that is too long is hashed as written",
            "h:f1e4628da5e66d537275a3d906f1373aac611a977c59790ac8c969cd4ae4994a",
        ) { bucket("x".repeat(255) + "\uD800") },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "a platform rule's id", "platform/api") {
            CompiledRateLimit.counterId(DocumentKind.PLATFORM, "", "api")
        },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "an app rule's id, its owner holding . and -", "app:order.v2-eu/api") {
            CompiledRateLimit.counterId(DocumentKind.APP, "order.v2-eu", "api")
        },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "a tenant rule's id, its owner holding . and -",
            "tenant:acme.eu-1/api"
        ) {
            CompiledRateLimit.counterId(DocumentKind.TENANT, "acme.eu-1", "api")
        },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "the bucket of a rule without a key", "global") {
            CompiledRateLimit.SHARED_BUCKET
        },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "the script's arguments of 0.1 a second over a minute: the quota per window, the window in milliseconds",
            "6.0 60000",
        ) { RateLimitSpec(0.1, 60).let { "${it.limitPerWindow} ${it.windowMillis}" } },
        Golden(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            "a fractional quota is passed as written: 0.5 a second over 3 seconds",
            "1.5 3000",
        ) { RateLimitSpec(0.5, 3).let { "${it.limitPerWindow} ${it.windowMillis}" } },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "the counter's key", "cosec:rate-limiter:api:k:alice") {
            limiter.keyOf("api", "k:alice")
        },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "the prefix of a hash-tagged deployment", "{cosec}:rate-limiter") {
            CoSecCacheSpecs.rateLimiterPrefix("{cosec}")
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "a token id", "cosec:token:revoked:jti-1") {
            storageKey("cosec", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-1"))
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "the value the token store writes", "true") {
            runBlocking { tokens.revoke("jti-written", Duration.ofMinutes(10)) }
            rawRead(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-written"))
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "the value the revocation reader reads", true) {
            rawWrite(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-read"), "true")
            runBlocking { tokens.reader.isRevoked("jti-read") }
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "a token id, hash-tagged", "{cosec}:token:revoked:jti-1") {
            storageKey("{cosec}", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-1"))
        },
        Golden(FrozenLayout.REVOKED_SUBJECT, "a principal of a tenant", "cosec:token:subject:revoked:4:acme:alice") {
            storageKey("cosec", CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, subject("acme", "alice"))
        },
        Golden(
            FrozenLayout.REVOKED_SUBJECT,
            "the value the token store writes: issuedBefore in epoch milliseconds",
            "1764000000123"
        ) {
            runBlocking {
                tokens.revokeSubject(
                    TenantId("acme"),
                    PrincipalId("written"),
                    Instant.parse("2025-11-24T16:00:00.123Z"),
                    Duration.ofMinutes(10)
                )
            }
            rawRead(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, subject("acme", "written"))
        },
        Golden(
            FrozenLayout.REVOKED_SUBJECT,
            "the value the revocation reader reads, as epoch milliseconds",
            Instant.parse("2025-11-24T16:00:00.123Z")
        ) {
            rawWrite(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, subject("acme", "read"), "1764000000123")
            runBlocking { tokens.reader.subjectRevokedBefore(TenantId("acme"), PrincipalId("read")) }
        },
        Golden(FrozenLayout.REVOKED_SUBJECT, "the length counts UTF-16 units", "3:a😀:b:c") {
            subject("a😀", "b:c")
        },
        Golden(FrozenLayout.REVOKED_SUBJECT, "the default tenant", "cosec:token:subject:revoked:3:(0):alice") {
            storageKey("cosec", CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, subject(TenantId.DEFAULT.value, "alice"))
        },
        Golden(
            FrozenLayout.API_KEY,
            "a key's record",
            "cosec:api-key:afbf7d3efa6d06981e5652c8b2f5ec754788823989b220c4b0713909dbbd858b",
        ) { RedisApiKeyStore.redisKey(CoSecCacheSpecs.apiKeyPrefix("cosec"), ApiKeys.hash("ck-test-1")) },
        Golden(FrozenLayout.REVOCATION_CHANNELS, "the tokens' channel", "cosec:token:inv:revoked") {
            channel("cosec", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME)
        },
        Golden(FrozenLayout.REVOCATION_CHANNELS, "the principals' channel", "cosec:token:subject:inv:revoked") {
            channel("cosec", CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME)
        },
        Golden(FrozenLayout.REVOCATION_CHANNELS, "the tokens' channel, hash-tagged", "{cosec}:token:inv:revoked") {
            channel("{cosec}", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME)
        },
        Golden(FrozenLayout.MEMBERSHIP_CHANNEL, "the memberships' channel", "cosec:inv:membership") {
            channelOf(MembershipInvalidations.keyspace("cosec"))
        },
        Golden(FrozenLayout.DOCUMENTS, "the platform document", "cosec:snapshot:platform") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME,
                SnapshotKeyEncoder.encode(SnapshotKey.Platform)
            )
        },
        Golden(FrozenLayout.DOCUMENTS, "an app document", "cosec:snapshot:app:order-service") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME,
                SnapshotKeyEncoder.encode(SnapshotKey.App("order-service")),
            )
        },
        Golden(FrozenLayout.DOCUMENTS, "a tenant document", "cosec:snapshot:tenant:acme") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME,
                SnapshotKeyEncoder.encode(SnapshotKey.Tenant("acme"))
            )
        },
        Golden(FrozenLayout.DOCUMENTS, "the documents' channel", "cosec:inv:snapshot") {
            channel("cosec", CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)
        },
        Golden(FrozenLayout.TOKEN_FAMILY, "a session", "cosec:token:family:sid-1") {
            storageKey("cosec", CoSecCacheSpecs.TOKEN_FAMILY_CACHE_NAME, "sid-1")
        },
        Golden(FrozenLayout.SESSION_INDEX, "the index's prefix", "cosec:token:sessions:") {
            CoSecCacheSpecs.sessionIndexPrefix("cosec")
        },
        Golden(FrozenLayout.SOCIAL_STATE, "the bindings' prefix", "cosec:oauth:binding:") {
            CoSecCacheSpecs.socialBindingPrefix("cosec")
        },
        Golden(FrozenLayout.SOCIAL_COUNT, "the count's key", "cosec:oauth:binding:!count") {
            CoSecCacheSpecs.socialBindingPrefix("cosec") + RedisSocialStateStore.COUNT_SUFFIX
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "a status key lives exactly an hour, with no jitter", "PT1H PT0S") {
            caches.getValue("cosec").runtime.policy(CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME)!!
                .let { "${it.ttl} ${it.ttlJitter}" }
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "an instance's status", "cosec:shadow:status:pod-a.1") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, "pod-a.1")
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "an instance's resident documents", "cosec:shadow:resident:pod-a.1") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_RESIDENT_CACHE_NAME, "pod-a.1")
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "the list of instances", "cosec:shadow:instances:all") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_INDEX_CACHE_NAME, ShadowCacheSpecs.INDEX_KEY)
        },
        Golden(
            FrozenLayout.CONTROL_REVISIONS,
            "a revision of a tenant document",
            "cosec:control:revision:tenant:acme:SHA-256:" + "0".repeat(64),
        ) {
            val key = RevisionRecordKey(
                SnapshotKey.Tenant("acme"),
                SnapshotDigest(SnapshotDigest.SHA_256, ByteArray(32))
            )
            storageKey("cosec", CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME, RevisionRecordKeyEncoder.encode(key))
        },
        Golden(FrozenLayout.CONTROL_REVISIONS, "a document's revision index", "cosec:control:revisions:tenant:acme") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME,
                SnapshotKeyEncoder.encode(SnapshotKey.Tenant("acme"))
            )
        },
        Golden(FrozenLayout.CONTROL_REVISIONS, "the revisions' channel", "cosec:control:inv:revision") {
            channel("cosec", CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME)
        },
        Golden(FrozenLayout.CONTROL_STAGE, "a document's staging slot", "cosec:control:stage:platform") {
            storageKey("cosec", CoSecCacheSpecs.STAGE_CACHE_NAME, SnapshotKeyEncoder.encode(SnapshotKey.Platform))
        },
        Golden(FrozenLayout.CONTROL_CHANGES, "a change", "cosec:control:change:c-1") {
            storageKey("cosec", CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME, "c-1")
        },
        Golden(FrozenLayout.CONTROL_CHANGES, "a document's open changes", "cosec:control:changes:doc:tenant:acme") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME,
                OpenChangeSets.documentKey(SnapshotKey.Tenant("acme"))
            )
        },
        Golden(FrozenLayout.CONTROL_CHANGES, "the open changes of a state", "cosec:control:changes:state:SHADOWING") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME,
                OpenChangeSets.stateKey(ChangeState.SHADOWING)
            )
        },
        Golden(FrozenLayout.CONTROL_CHANGES, "the marker that the sets are kept", "cosec:control:changes:kept") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME, OpenChangeSets.KEPT)
        },
        Golden(FrozenLayout.CONTROL_CHANGES, "a document's ended changes", "cosec:control:changes:ended:platform") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME,
                OpenChangeSets.endedKey(SnapshotKey.Platform)
            )
        },
        Golden(FrozenLayout.CONTROL_REFERENCES, "who refers to a platform policy", "cosec:control:refs:platform:p-1") {
            val key = ReferenceKey(SnapshotKey.Platform, PolicyId("p-1"))
            storageKey("cosec", CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME, ReferenceKeyEncoder.encode(key))
        },
        Golden(FrozenLayout.CONTROL_REFERENCES, "the references' channel", "cosec:control:inv:refs") {
            channel("cosec", CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
        },
        Golden(FrozenLayout.CONTROL_LISTS, "the document list", "cosec:control:documents:all") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_DOCUMENT_SET_CACHE_NAME, ControlSets.DOCUMENT_SET)
        },
        Golden(FrozenLayout.CONTROL_LISTS, "the app list", "cosec:control:apps:all") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME, CoCacheControlStore.APP_LIST)
        },
        Golden(FrozenLayout.CONTROL_LISTS, "the lists' channel", "cosec:control:inv:documents") {
            channel("cosec", CoSecCacheSpecs.CONTROL_DOCUMENT_SET_CACHE_NAME)
        },
        Golden(
            FrozenLayout.LEGACY_LISTS,
            "the open-change list before 8.0.0-beta.5",
            "cosec:control:list:open-changes"
        ) {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME, CoCacheControlStore.OPEN_CHANGES)
        },
        Golden(FrozenLayout.LEGACY_LISTS, "the document list before 8.0.0-beta.5", "cosec:control:list:documents") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME, CoCacheControlStore.DOCUMENTS)
        },
        Golden(FrozenLayout.LEGACY_LISTS, "the old lists' channel", "cosec:control:inv:list") {
            channel("cosec", CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME)
        },
        Golden(
            FrozenLayout.LEGACY_LISTS,
            "a bare list is written as entry to time, sorted, with no other member",
            """{"a":"2026-11-24T00:00:00Z","b":"2026-11-24T00:00:01Z"}"""
        ) {
            ListedCodec.encode(
                mapOf("b" to Instant.parse("2026-11-24T00:00:01Z"), "a" to Instant.parse("2026-11-24T00:00:00Z"))
            )
                .decodeToString()
        },
        Golden(
            FrozenLayout.LEGACY_LISTS,
            "a bare list as an older release wrote it reads as its entries",
            "{c-1=2026-11-24T00:00:00Z}"
        ) {
            ListedCodec.decode("""{"c-1":"2026-11-24T00:00:00Z"}""".encodeToByteArray()).toString()
        },
        Golden(FrozenLayout.CONTROL_LEASES, "the reclaim pass's lease", "cosec:control:lease:reclaim-pass") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME, ControlMaintenance.RECLAIM_LEASE)
        },
        Golden(FrozenLayout.CONTROL_LEASES, "a document's reclaim lease", "cosec:control:lease:reclaim:tenant:acme") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME,
                ReclaimLease.of(SnapshotKey.Tenant("acme"))
            )
        },
        Golden(FrozenLayout.CONTROL_LEASES, "the platform document's reclaim lease", "reclaim:platform") {
            ReclaimLease.of(SnapshotKey.Platform)
        },
        Golden(FrozenLayout.CONTROL_LEASES, "an app document's reclaim lease", "reclaim:app:order") {
            ReclaimLease.of(SnapshotKey.App("order"))
        },
        Golden(FrozenLayout.CONTROL_LEASES, "cosec-migrate's repair lease", "cosec:control:lease:repair-references") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME, "repair-references")
        },
        Golden(FrozenLayout.CONTROL_LEASES, "the shadow windows' lease", "cosec:control:lease:shadow-windows") {
            storageKey("cosec", CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME, ControlMaintenance.SHADOW_LEASE)
        },
        Golden(FrozenLayout.CANDIDATES, "a staged candidate", "cosec:candidate:tenant:acme") {
            storageKey(
                "cosec",
                CoSecCacheSpecs.CANDIDATE_CACHE_NAME,
                SnapshotKeyEncoder.encode(SnapshotKey.Tenant("acme"))
            )
        },
        Golden(FrozenLayout.CANDIDATES, "the staged list", "cosec:candidate-staged:all") {
            storageKey("cosec", CoSecCacheSpecs.CANDIDATE_STAGED_CACHE_NAME, ControlSets.STAGED_SET)
        },
        Golden(FrozenLayout.CANDIDATES, "the candidates' channel", "cosec:inv:candidate") {
            channel("cosec", CoSecCacheSpecs.CANDIDATE_CACHE_NAME)
        },
        Golden(FrozenLayout.SHADOW_REPORTS, "an instance's report of a window", "cosec:shadow:report:c1@1:pod-a.1") {
            storageKey("cosec", CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME, ShadowCacheSpecs.reportKey("c1@1", "pod-a.1"))
        },
        Golden(FrozenLayout.SHADOW_REPORTS, "who reported on a window", "cosec:shadow:reporters:c1@1") {
            storageKey("cosec", CoSecCacheSpecs.SHADOW_REPORTER_CACHE_NAME, "c1@1")
        },
        Golden(FrozenLayout.SHADOW_REPORTS, "the reports' channel", "cosec:shadow:inv:report") {
            channel("cosec", CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME)
        },
    )

    private fun subject(tenant: String, principal: String) = RevokedSubjectCache.key(
        TenantId(tenant),
        PrincipalId(principal)
    )

    @TestFactory
    fun goldenSamples(): List<DynamicTest> = goldens.map { golden ->
        DynamicTest.dynamicTest("${golden.entry}: ${golden.sample}") { golden.check() }
    }

    /** Every entry of the one list has samples here or in `FrozenScriptsTest`, which needs Redis. */
    @Test
    fun everyEntryIsHeld() {
        (goldens.map { it.entry } + FrozenScriptsTest.ENTRIES).toSet().assert().isEqualTo(FrozenLayout.entries.toSet())
    }

    @Test
    fun aFailureSaysTheFormatIsFrozen() {
        frozen(FrozenLayout.REVOKED_TOKEN, "a token id", "a", "b").assert()
            .contains(
                "The key of a token's revocation and its value is a format frozen within 8.x (RFC 0014 R6, SNP-26"
            )
            .contains("Changing it needs a new key space")
            .contains("lets through what both releases refuse")
    }
}
