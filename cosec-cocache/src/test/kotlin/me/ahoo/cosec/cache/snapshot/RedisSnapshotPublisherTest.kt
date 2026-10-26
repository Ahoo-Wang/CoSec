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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotChangeListener
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RedisSnapshotPublisher]'s read-modify-write on real CoCache engines (the in-memory backend; F12 runs on Redis in
 * [SnapshotPublisherConcurrencyTest]).
 */
class RedisSnapshotPublisherTest {
    private val backend = InMemoryBackend()
    private val keyPrefix = newKeyPrefix()

    private fun policy(id: String, type: PolicyType = PolicyType.CUSTOM) = PolicyData(id, "c", id, "", type, "t")

    private fun SnapshotNode.document(key: SnapshotKey): SnapshotDocument? =
        (store.readIfChanged(key, null).get() as? SnapshotRead.Present)?.document

    private fun SnapshotNode.content(key: SnapshotKey): SnapshotContent =
        SnapshotContent.read(key, checkNotNull(document(key)))

    private fun SnapshotNode.policyIds(): List<String> =
        (content(SnapshotKey.Policies) as SnapshotContent.Policies).policies.keys.toList()

    private fun SnapshotNode.grants(appId: String): Map<String, String> =
        (content(SnapshotKey.App(appId)) as SnapshotContent.App).grants.mapValues { it.value.toString() }

    @Test
    fun setPolicyAddsAndReplacesWithTheNextRevision() {
        SnapshotNode(backend, keyPrefix).use { node ->
            node.publisher.setPolicy(policy("b")).block()
            node.publisher.setPolicy(policy("a", PolicyType.GLOBAL)).block()
            node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(2L)
            node.policyIds().assert().containsExactly("a", "b")

            node.publisher.setPolicy(policy("a", PolicyType.CUSTOM)).block()
            node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(3L)
            val stored = (node.content(SnapshotKey.Policies) as SnapshotContent.Policies).policies.getValue("a")
            CoSecJsonSerializer.treeToValue(SnapshotContent.treeOf(stored), Policy::class.java).type
                .assert().isEqualTo(PolicyType.CUSTOM)
        }
    }

    @Test
    fun setPolicyRefusesTheHistoryPrefixAndWritesNothing() {
        SnapshotNode(backend, keyPrefix).use { node ->
            assertThrows<IllegalArgumentException> { node.publisher.setPolicy(policy("history:p")).block() }
            node.document(SnapshotKey.Policies).assert().isNull()
        }
    }

    @Test
    fun appPermissionsAndGrantsShareTheAppDocument() {
        SnapshotNode(backend, keyPrefix).use { node ->
            // grants without an app permission would not apply, and a document has no place for them
            assertThrows<IllegalStateException> {
                node.publisher.setRoleGrants("app", "r", SpaceIdCapable.DEFAULT, setOf("p1")).block()
            }
            node.publisher.setAppPermission(appPermission("app")).block()
            node.publisher.setRoleGrants("app", "viewer", SpaceIdCapable.DEFAULT, setOf("p2", "p1")).block()
            node.publisher.setRoleGrants("app", "viewer", "team", setOf("p1")).block()
            node.grants("app").assert().isEqualTo(mapOf("viewer" to """["p1","p2"]""", "viewer@team" to """["p1"]"""))

            // a new app permission keeps the grants
            node.publisher.setAppPermission(appPermission("app", "p1", "p2", "p3")).block()
            node.grants("app").keys.assert().containsExactly("viewer", "viewer@team")
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(4L)

            node.publisher.removeRoleGrants("app", "viewer", "team").block()
            node.grants("app").keys.assert().containsExactly("viewer")
            // removing what is not there writes nothing
            node.publisher.removeRoleGrants("app", "viewer", "team").block()
            node.publisher.removeRoleGrants("absent", "viewer", SpaceIdCapable.DEFAULT).block()
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(5L)
            node.document(SnapshotKey.App("absent")).assert().isNull()
        }
    }

    @Test
    fun refusesKeysThatWouldBeAmbiguous() {
        SnapshotNode(backend, keyPrefix).use { node ->
            node.publisher.setAppPermission(appPermission("app")).block()
            assertThrows<IllegalArgumentException> {
                node.publisher.setRoleGrants("app", "a@b", SpaceIdCapable.DEFAULT, setOf("p1")).block()
            }
            assertThrows<IllegalArgumentException> {
                node.publisher.removeRoleGrants("app:x", "r", SpaceIdCapable.DEFAULT).block()
            }
            assertThrows<IllegalArgumentException> { node.publisher.setAppPermission(appPermission("a:b")).block() }
        }
    }

    /**
     * Review: `AppSpacedRoleId` does not check the space, so a space containing `@` made the key `r@a@b`, which the
     * compiler rejects for the whole app. The publisher refuses it and writes nothing.
     */
    @Test
    fun refusesASpaceTheCompilerCouldNotReadBack() {
        SnapshotNode(backend, keyPrefix).use { node ->
            node.publisher.setAppPermission(appPermission("app")).block()
            listOf("a@b", "@").forEach { space ->
                assertThrows<IllegalArgumentException> {
                    node.publisher.setRoleGrants("app", "r", space, setOf("p1")).block()
                }
                assertThrows<IllegalArgumentException> { node.publisher.removeRoleGrants("app", "r", space).block() }
            }
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(1L)
        }
    }

    @Test
    fun batchesChangeADocumentOnce() {
        SnapshotNode(backend, keyPrefix).use { node ->
            node.publisher.setPolicies(listOf(policy("a"), policy("b"), policy("c"))).block()
            node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(1L)
            node.policyIds().assert().containsExactly("a", "b", "c")
            assertThrows<IllegalArgumentException> {
                node.publisher.setPolicies(
                    listOf(policy("a"), policy("a"))
                ).block()
            }
            // one bad policy fails the batch: none of it is written
            assertThrows<IllegalArgumentException> {
                node.publisher.setPolicies(listOf(policy("d"), policy("history:e"))).block()
            }
            node.publisher.setPolicies(emptyList()).block()
            node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(1L)

            node.publisher.setAppPermission(appPermission("app")).block()
            node.publisher.setRoleGrants(
                "app",
                mapOf(SpacedRoleId("r1") to setOf("p1"), SpacedRoleId("r2", "team") to setOf("p1")),
            ).block()
            node.grants("app").keys.assert().containsExactly("r1", "r2@team")
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(2L)
            node.publisher.setRoleGrants("app", emptyMap()).block()
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(2L)
        }
    }

    /** Review: a write that changes nothing publishes nothing: no revision, no reload anywhere. */
    @Test
    fun aWriteThatChangesNothingPublishesNothing() {
        SnapshotNode(backend, keyPrefix).use { node ->
            val published = CopyOnWriteArrayList<SnapshotKey>()
            val publisher = RedisSnapshotPublisher(node.cache, { published += it })
            publisher.setPolicy(policy("a")).block()
            publisher.setAppPermission(appPermission("app")).block()
            publisher.setRoleGrants("app", "r", SpaceIdCapable.DEFAULT, setOf("p1")).block()
            published.clear()

            publisher.setPolicy(policy("a")).block()
            publisher.setPolicies(listOf(policy("a"))).block()
            publisher.setAppPermission(appPermission("app")).block()
            publisher.setRoleGrants("app", "r", SpaceIdCapable.DEFAULT, setOf("p1")).block()
            published.assert().isEmpty()
            node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(1L)
            node.document(SnapshotKey.App("app"))!!.revision.assert().isEqualTo(2L)

            publisher.setRoleGrants("app", "r", SpaceIdCapable.DEFAULT, setOf("p1", "p2")).block()
            published.assert().containsExactly(SnapshotKey.App("app"))
        }
    }

    /** Review: entries the publisher does not touch keep their stored bytes, numbers a tree would rewrite included. */
    @Test
    fun keepsTheBytesOfEntriesItDoesNotTouch() {
        RawSnapshotWriter(backend, keyPrefix).use { raw ->
            SnapshotNode(backend, keyPrefix).use { node ->
                val odd = """{"id":"z","x":[1e400,0.12345678901234567890123,1.10]}"""
                raw.put(
                    SnapshotKey.Policies,
                    """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[$odd]}"""
                )
                node.publisher.setPolicy(policy("a")).block()
                node.document(SnapshotKey.Policies)!!.bodyBytes().decodeToString().assert().contains(odd)
            }
        }
    }

    /** Review: documents are changed and serialized on the publisher's scheduler, not on the thread that completed I/O. */
    @Test
    fun changesDocumentsOnItsScheduler() {
        val scheduler = Schedulers.newSingle("publisher-test")
        try {
            SnapshotNode(backend, keyPrefix).use { node ->
                val threads = CopyOnWriteArrayList<String>()
                val clock = object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant {
                        threads += Thread.currentThread().name
                        return Instant.now()
                    }
                }
                RedisSnapshotPublisher(node.cache, clock = clock, scheduler = scheduler).setPolicy(policy("a")).block()
                threads.assert().isNotEmpty()
                threads.forEach { it.assert().startsWith("publisher-test") }
            }
        } finally {
            scheduler.dispose()
        }
    }

    /**
     * Review: the read of a read-modify-write goes to Redis, not to a local copy. Here the writer's local copy is
     * stale and never told (a silent channel): reading it would conflict on every attempt.
     */
    @Test
    fun readsRedisNotAStaleLocalCopy() {
        SnapshotNode(SilentChannelBackend(backend), keyPrefix).use { stale ->
            SnapshotNode(backend, keyPrefix).use { other ->
                other.publisher.setPolicy(policy("a")).block()
                (stale.store.read(SnapshotKey.Policies).get() as SnapshotRead.Present).document.revision
                    .assert().isEqualTo(1L)
                other.publisher.setPolicy(policy("b")).block()

                val publisher = RedisSnapshotPublisher(stale.cache, maxAttempts = 1)
                publisher.setPolicy(policy("c")).block()
                publisher.conflicts.assert().isZero()
                other.policyIds().assert().containsExactly("a", "b", "c")
            }
        }
    }

    @Test
    fun backsOffBetweenConflictingAttempts() {
        SnapshotNode(backend, keyPrefix).use { node ->
            SnapshotNode(backend, keyPrefix).use { other ->
                node.publisher.setPolicy(policy("a")).block()
                val racing =
                    racingCache(node.cache, times = 3) { other.publisher.setPolicy(policy("other$it")).block() }
                val backoff = Duration.ofMillis(10)
                val publisher =
                    RedisSnapshotPublisher(racing, retryBackoff = backoff, maxRetryBackoff = Duration.ofMillis(25))
                val bounds = CopyOnWriteArrayList<Long>()
                // the longest wait each time, so the waits are measurable
                publisher.jitter = { bound -> bound.also { bounds += it } }
                val started = System.nanoTime()
                publisher.setPolicy(policy("b")).block()
                publisher.conflicts.assert().isEqualTo(3L)
                // the bound doubles from retryBackoff and stops at maxRetryBackoff
                bounds.assert().containsExactly(10_000_000L, 20_000_000L, 25_000_000L)
                Duration.ofNanos(System.nanoTime() - started).assert().isGreaterThanOrEqualTo(Duration.ofMillis(55))
                node.policyIds().assert().contains("b")
                assertThrows<IllegalArgumentException> {
                    RedisSnapshotPublisher(node.cache, retryBackoff = Duration.ofSeconds(1), maxRetryBackoff = backoff)
                }
                assertThrows<IllegalArgumentException> {
                    RedisSnapshotPublisher(node.cache, retryBackoff = Duration.ofMillis(-1))
                }
            }
        }
    }

    @Test
    fun keepsEntriesItCannotDecodeAsTheyWere() {
        SnapshotNode(backend, keyPrefix).use { node ->
            // a policy with a matcher type this instance lacks, as a newer writer may have published
            val foreign = """{"id":"z","type":"custom","statements":[{"action":{"newer-type":{}}}]}"""
            node.publisher.replace(
                SnapshotContent.Policies(TreeMap(mapOf("z" to CoSecJsonSerializer.readTree(foreign))))
            ).block()
            node.publisher.setPolicy(policy("a")).block()
            val policies = (node.content(SnapshotKey.Policies) as SnapshotContent.Policies).policies
            policies.keys.assert().containsExactly("a", "z")
            SnapshotContent.treeOf(policies.getValue("z")).assert().isEqualTo(CoSecJsonSerializer.readTree(foreign))
        }
    }

    @Test
    fun aMalformedDocumentFailsTheWriteAndStays() {
        RawSnapshotWriter(backend, keyPrefix).use { raw ->
            SnapshotNode(backend, keyPrefix).use { node ->
                // decodable header, but the policies section is missing: a writer must not "repair" it
                val malformed = """{"schema":1,"revision":3,"publishedAt":"2026-10-21T08:00:00Z"}"""
                raw.put(SnapshotKey.Policies, malformed)
                assertThrows<SnapshotFormatException> { node.publisher.setPolicy(policy("a")).block() }
                raw.cache[SnapshotKey.Policies]!!.decodeToString().assert().isEqualTo(malformed)
            }
        }
    }

    @Test
    fun anUndecodableValueFailsTheWriteAndStays() {
        RawSnapshotWriter(backend, keyPrefix).use { raw ->
            SnapshotNode(backend, keyPrefix).use { node ->
                raw.put(SnapshotKey.Policies, "not json")
                val error = assertThrows<RuntimeException> { node.publisher.setPolicy(policy("a")).block() }
                generateSequence<Throwable>(error) { it.cause }.any { it is SnapshotFormatException }.assert().isTrue()
                raw.cache[SnapshotKey.Policies]!!.decodeToString().assert().isEqualTo("not json")
            }
        }
    }

    /**
     * Review requirement: after a conflict, the write starts over from a fresh conditional read, never from the
     * conflict's digest. Here the racing writer leaves a value that cannot be decoded: the fresh read fails, so the
     * publish fails and the value stays. Writing over `Conflict.current` would have replaced it unseen.
     */
    @Test
    fun aConflictWithAnUndecodableValueIsNeverOverwritten() {
        RawSnapshotWriter(backend, keyPrefix).use { raw ->
            SnapshotNode(backend, keyPrefix).use { node ->
                node.publisher.setPolicy(policy("a")).block()
                val racing = racingCache(node.cache) { raw.put(SnapshotKey.Policies, "{broken") }
                val publisher = RedisSnapshotPublisher(racing, node.store)
                assertThrows<RuntimeException> { publisher.setPolicy(policy("b")).block() }
                publisher.conflicts.assert().isEqualTo(1L)
                raw.cache[SnapshotKey.Policies]!!.decodeToString().assert().isEqualTo("{broken")
            }
        }
    }

    @Test
    fun aConflictStartsOverFromWhatTheOtherWriterWrote() {
        SnapshotNode(backend, keyPrefix).use { node ->
            SnapshotNode(backend, keyPrefix).use { other ->
                node.publisher.setPolicy(policy("a")).block()
                val racing =
                    racingCache(node.cache, times = 2) { other.publisher.setPolicy(policy("other$it")).block() }
                val published = CopyOnWriteArrayList<SnapshotKey>()
                val publisher = RedisSnapshotPublisher(racing, { key -> published += key })
                publisher.setPolicy(policy("b")).block()
                publisher.conflicts.assert().isEqualTo(2L)
                published.assert().containsExactly(SnapshotKey.Policies)
                node.policyIds().assert().containsExactly("a", "b", "other1", "other2")
                node.document(SnapshotKey.Policies)!!.revision.assert().isEqualTo(4L)
            }
        }
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        SnapshotNode(backend, keyPrefix).use { node ->
            SnapshotNode(backend, keyPrefix).use { other ->
                val racing = racingCache(node.cache, times = Int.MAX_VALUE) {
                    other.publisher.setPolicy(policy("other$it")).block()
                }
                val publisher = RedisSnapshotPublisher(racing, maxAttempts = 3)
                val error = assertThrows<SnapshotPublishConflictException> { publisher.setPolicy(policy("b")).block() }
                error.attempts.assert().isEqualTo(3)
                node.policyIds().assert().doesNotContain("b")
            }
        }
    }

    @Test
    fun aWriteThatFailedIsReportedAndChangesNothing() {
        val hooked = HookedBackend(InMemoryBackend())
        SnapshotNode(hooked, keyPrefix).use { node ->
            node.publisher.setPolicy(policy("a")).block()
            hooked.failWrites = true
            assertThrows<CacheWriteException> { node.publisher.setPolicy(policy("b")).block() }
            hooked.failWrites = false
            node.policyIds().assert().containsExactly("a")
        }
    }

    @Test
    fun aWriteThatLandedButReportedAnErrorSucceeds() {
        SnapshotNode(backend, keyPrefix).use { node ->
            // the write reaches Redis, the reply does not: the publisher reads its own digest back
            val lostReply = wrap(node.cache) { delegate ->
                object : AsyncCache<SnapshotKey, SnapshotDocument> by delegate {
                    override fun putIfMatch(
                        key: SnapshotKey,
                        expected: Digest?,
                        value: SnapshotDocument
                    ): CompletableFuture<ConditionalPut> = delegate.putIfMatch(key, expected, value).thenCompose {
                        CompletableFuture.failedFuture(
                            CacheWriteException(delegate.name, "putIfMatch", IllegalStateException("timeout"))
                        )
                    }
                }
            }
            val published = CopyOnWriteArrayList<SnapshotKey>()
            RedisSnapshotPublisher(lostReply, { published += it }).setPolicy(policy("a")).block()
            published.assert().containsExactly(SnapshotKey.Policies)
            node.policyIds().assert().containsExactly("a")
        }
    }

    @Test
    fun theWriterHearsOfItsOwnWriteAtOnce() {
        SnapshotNode(SilentChannelBackend(backend), keyPrefix).use { node ->
            val changed = CopyOnWriteArrayList<SnapshotKey>()
            node.store.subscribe(object : SnapshotChangeListener {
                override fun onChanged(keys: Collection<SnapshotKey>) {
                    changed.addAll(keys)
                }

                override fun onReset() = Unit
            })
            node.publisher.setPolicy(policy("a")).block()
            changed.assert().containsExactly(SnapshotKey.Policies)
        }
    }

    @Test
    fun refusesNonPositiveAttempts() {
        SnapshotNode(backend, keyPrefix).use { node ->
            assertThrows<IllegalArgumentException> { RedisSnapshotPublisher(node.cache, maxAttempts = 0) }
        }
    }

    /** [cache] with its async view replaced by [view]. */
    private fun wrap(
        cache: PolicySnapshotCache,
        view: (AsyncCache<SnapshotKey, SnapshotDocument>) -> AsyncCache<SnapshotKey, SnapshotDocument>,
    ): PolicySnapshotCache {
        val async = view(cache.async())
        val delegate = object : Cache<SnapshotKey, SnapshotDocument> by cache {
            override fun async(): AsyncCache<SnapshotKey, SnapshotDocument> = async
        }
        return PolicySnapshotCache(delegate, cache.namespace, cache.localCopyMaxAge, cache.channel)
    }

    /** [cache], where another write ([race]) lands between each of the first [times] reads and writes. */
    private fun racingCache(cache: PolicySnapshotCache, times: Int = 1, race: (Int) -> Unit): PolicySnapshotCache {
        val raced = AtomicInteger()
        return wrap(cache) { delegate ->
            object : AsyncCache<SnapshotKey, SnapshotDocument> by delegate {
                override fun putIfMatch(
                    key: SnapshotKey,
                    expected: Digest?,
                    value: SnapshotDocument
                ): CompletableFuture<ConditionalPut> {
                    val count = raced.incrementAndGet()
                    if (count <= times) {
                        race(count)
                    }
                    return delegate.putIfMatch(key, expected, value)
                }
            }
        }
    }
}
