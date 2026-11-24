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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.engine.CompiledRateLimit
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance

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
    fun close() = caches.values.forEach(CoSecTestCaches::close)

    private fun admin(prefix: String, cacheName: String) = checkNotNull(
        caches.getValue(prefix).runtime.admin(cacheName)
    )

    private fun storageKey(prefix: String, cacheName: String, encodedKey: String): String =
        admin(prefix, cacheName).inspect(encodedKey).storageKey

    private fun channel(prefix: String, cacheName: String): String = channelOf(admin(prefix, cacheName).keyspace)

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
            each.factory.createLocked(CoSecCacheSpecs.policySnapshot(prefix))
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
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "the counter's key", "cosec:rate-limiter:api:k:alice") {
            limiter.keyOf("api", "k:alice")
        },
        Golden(FrozenLayout.RATE_LIMIT_COUNTERS, "the prefix of a hash-tagged deployment", "{cosec}:rate-limiter") {
            CoSecCacheSpecs.rateLimiterPrefix("{cosec}")
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "a token id", "cosec:token:revoked:jti-1") {
            storageKey("cosec", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-1"))
        },
        Golden(FrozenLayout.REVOKED_TOKEN, "a token id, hash-tagged", "{cosec}:token:revoked:jti-1") {
            storageKey("{cosec}", CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("jti-1"))
        },
        Golden(FrozenLayout.REVOKED_SUBJECT, "a principal of a tenant", "cosec:token:subject:revoked:4:acme:alice") {
            storageKey("cosec", CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, subject("acme", "alice"))
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
        Golden(FrozenLayout.INSTANCE_STATUS, "an instance's status", "cosec:shadow:status:pod-a.1") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, "pod-a.1")
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "an instance's resident documents", "cosec:shadow:resident:pod-a.1") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_RESIDENT_CACHE_NAME, "pod-a.1")
        },
        Golden(FrozenLayout.INSTANCE_STATUS, "the list of instances", "cosec:shadow:instances:all") {
            storageKey("cosec", CoSecCacheSpecs.INSTANCE_INDEX_CACHE_NAME, ShadowCacheSpecs.INDEX_KEY)
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
            .contains("The key of a token's revocation is a format frozen within 8.x (RFC 0014 R6, SNP-26")
            .contains("Changing it needs a new key space")
            .contains("lets through what both releases refuse")
    }
}
