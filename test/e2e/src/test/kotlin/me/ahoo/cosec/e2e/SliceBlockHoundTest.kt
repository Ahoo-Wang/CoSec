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

package me.ahoo.cosec.e2e

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.InMemoryBlacklistSource
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.attribute.AttributeRequest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * No blocking on the event loop (AGENTS.md): once the documents are installed, a request decides on the thread that
 * received it, without blocking and without suspending (RFC 0006 §7.2). BlockHound watches threads named like a
 * non-blocking caller; the allowances are those of `SnapshotStoreBlockHoundTest`.
 */
class SliceBlockHoundTest {
    @Test
    fun `blockhound watches the test threads`() {
        BlockHoundWatch.clear()
        BlockHoundWatch.onNonBlockingThread { runCatching { Thread.sleep(1) } }
        BlockHoundWatch.violations.assert().isNotEmpty()
    }

    @Test
    fun `a warm request decides on its thread without blocking or suspending`() {
        Slice().use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}],"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            assertWarmRequestNeitherBlocksNorSuspends(slice)
        }
    }

    /** T1 (RFC 0006 §7.2): providers that answer at once add no suspension, in stage A and in stage B. */
    @Test
    fun `a warm request with the blacklist and an external attribute neither blocks nor suspends`() {
        val inFence = object : AttributeProvider {
            override val attributes = listOf(AttributeDeclaration("ext.geo.inFence", AttributeType.BOOL))

            override suspend fun fetch(request: AttributeRequest, names: Set<String>) = mapOf("ext.geo.inFence" to true)
        }
        val providers = AttributeProviders(listOf(BlacklistAttributeProvider(InMemoryBlacklistSource()), inFence))
        Slice(attributes = providers).use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":{"eq":["ext.geo.inFence",true]}}]}],""" +
                    """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            assertWarmRequestNeitherBlocksNorSuspends(slice)
        }
    }

    /** RFC 0006 §9.3: a BEST_EFFORT listener's event is offered without suspending, and handled elsewhere. */
    @Test
    fun `a warm request with a best-effort listener neither blocks nor suspends`() {
        val handled = CompletableFuture<String>()
        val listener = object : DecisionListener {
            override val name = "probe"

            override suspend fun onDecision(event: DecisionEvent) {
                handled.complete(Thread.currentThread().name)
            }
        }
        Slice(listeners = DecisionListeners(listOf(listener))).use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}],"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            assertWarmRequestNeitherBlocksNorSuspends(slice)
            handled.get(
                BlockHoundWatch.TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            ).assert().doesNotStartWith(BlockHoundWatch.THREAD_PREFIX)
        }
    }

    /**
     * RFC 0009 §4.2: an API key asks the membership provider on every request; `CoCacheMembershipProvider` answers a
     * local hit on the caller's thread, so a warm key request neither blocks nor suspends either. The provider is asked
     * whatever the binding: an unknown principal would refuse the key.
     */
    @Test
    fun `a warm API key request neither blocks nor suspends`() {
        val key = ApiKeys.generate()
        val keys = InMemoryApiKeyStore(mapOf(ApiKeys.hash(key) to ApiKey("k1", "svc", "acme", setOf("reader")))).reader
        val loader = MembershipLoader { _, _ ->
            Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("reader"))), emptyList())
        }
        Slice(authenticator = ApiKeyAuthenticator(keys), membershipLoader = loader).use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}],""" +
                    """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            assertWarmRequestNeitherBlocksNorSuspends(slice, apiKeyRequest("GET", "/orders/1", key))
        }
    }

    private fun assertWarmRequestNeitherBlocksNorSuspends(
        slice: Slice,
        request: HttpFacts = request("GET", "/orders/1", "alice@acme"),
    ) {
        slice.publish(SnapshotKey.Tenant("acme"), "")
        // Warm the source: the documents are compiled and installed before the measured call.
        val warm = runBlocking { slice.evaluate(request) }
        warm.proceed.assert().describedAs(warm.toString()).isTrue()
        // A cache the first request filled can be invalidated once more by its own write's message, arriving later: wait
        // until a request completes on its thread, so the measured one is warm. One that never does fails here.
        val deadline = System.nanoTime() + WARM_TIMEOUT.toNanos()
        while (!start(slice, request).isDone) {
            check(System.nanoTime() < deadline) { "The request never completed on its thread within $WARM_TIMEOUT." }
            Thread.sleep(WARM_POLL_MILLIS)
        }
        BlockHoundWatch.clear()
        val outcome = BlockHoundWatch.onNonBlockingThread {
            val result = start(slice, request)
            // Completed before startCoroutine returned: nothing suspended, nothing was dispatched.
            check(result.isDone) { "The warm request suspended." }
            result.join()
        }
        outcome.proceed.assert().isTrue()
        BlockHoundWatch.violations.assert().isEmpty()
    }

    /** Starts [request] on the calling thread: done on return exactly when nothing suspended. */
    private fun start(slice: Slice, request: HttpFacts): CompletableFuture<Outcome> {
        val result = CompletableFuture<Outcome>()
        val evaluate: suspend () -> Outcome = { slice.evaluate(request) }
        evaluate.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
        return result
    }

    private companion object {
        val WARM_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val WARM_POLL_MILLIS = 20L
    }
}
