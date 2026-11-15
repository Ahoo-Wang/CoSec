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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures.InstalledViews
import me.ahoo.cosec.runtime.PipelineFixtures.TENANT
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.attribute.AttributeRequest
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The two forms of [Pipeline.builder]: what `awaitDocuments` answers, and what `close` closes. */
class PipelineBuilderTest {
    private val tenant = SnapshotKey.Tenant(TENANT.value)

    /**
     * The documents of [inner]; a read of a held key waits for [release], a read of a broken key fails. [coldReads]
     * counts the full reads of each key, which a load starts; a revalidation is a `readIfChanged`.
     */
    private class HeldStore : SnapshotDocumentStore {
        val inner = InMemorySnapshotDocumentStore()
        val held = ConcurrentHashMap<SnapshotKey, CompletableFuture<Unit>>()
        val broken: MutableSet<SnapshotKey> = ConcurrentHashMap.newKeySet()
        val coldReads = ConcurrentHashMap<SnapshotKey, AtomicInteger>()

        fun hold(key: SnapshotKey) {
            held[key] = CompletableFuture()
        }

        fun release(key: SnapshotKey) {
            held.getValue(key).complete(Unit)
        }

        private fun answer(key: SnapshotKey, read: () -> CompletableFuture<SnapshotRead>): CompletableFuture<SnapshotRead> {
            if (key in broken) {
                return CompletableFuture.failedFuture(IllegalStateException("unreadable"))
            }
            return held[key]?.thenCompose { read() } ?: read()
        }

        override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> {
            coldReads.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            return answer(key) { inner.read(key) }
        }

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?) =
            answer(key) { inner.readIfChanged(key, known) }

        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = inner.subscribe(listener)
    }

    private fun document(body: String = ""): SnapshotDocument {
        val publishedAt = Instant.parse("2026-11-01T00:00:00Z")
        return SnapshotDocument(
            2,
            1,
            publishedAt,
            """{"schema":2,"revision":1,"publishedAt":"$publishedAt"$body}""".encodeToByteArray(),
        )
    }

    private fun store() = HeldStore().apply {
        inner.put(SnapshotKey.Platform, document(ALLOW_EVERYONE))
        inner.put(tenant, document())
    }

    private fun pipeline(store: SnapshotDocumentStore, listener: DecisionListener? = null) =
        Pipeline.builder(authenticator(authenticated()), store)
            .snapshots {
                coldLoadTimeout = Duration.ofMillis(50)
                prewarmTenants = setOf(TENANT.value)
            }
            .apply { listener?.let(::listener) }
            .build()

    @Test
    fun `before awaitDocuments a tenant not yet loaded is a 503, after it the request is decided`() = runBlocking<Unit> {
        val store = store().apply { hold(tenant) }
        pipeline(store).use { pipeline ->
            pipeline.evaluate(facts()).status.assert().isEqualTo(503)
            store.release(tenant)
            pipeline.awaitDocuments().assert().isEmpty()
            pipeline.evaluate(facts()).status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a prewarmed document that cannot be read is missing, and its requests stay 503`() = runBlocking<Unit> {
        val store = store().apply { broken += tenant }
        pipeline(store).use { pipeline ->
            pipeline.awaitDocuments().assert().containsExactly(tenant)
            pipeline.evaluate(facts()).status.assert().isEqualTo(503)
        }
    }

    @Test
    fun `cancelling awaitDocuments cancels nothing of the load`() = runBlocking<Unit> {
        val store = store().apply { hold(tenant) }
        pipeline(store).use { pipeline ->
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { pipeline.awaitDocuments() }
            waiting.cancelAndJoin()
            store.release(tenant)
            pipeline.awaitDocuments().assert().isEmpty()
            // The load the cancelled wait started went on and installed the document: no second load read it again.
            store.coldReads.getValue(tenant).get().assert().isEqualTo(1)
            pipeline.evaluate(facts()).status.assert().isEqualTo(200)
        }
    }

    /** `settled` ends on every path: a close while the platform loads, and a load that throws as it starts. */
    @Test
    fun `the wait ends when the source closes or a load cannot start`() {
        val compiler = PlatformCompiler()
        val held = store().apply { hold(SnapshotKey.Platform) }
        val closing = DocumentSnapshots.source(held, compiler, SnapshotOptions())
        val wait = closing.settled(listOf(tenant))
        closing.close()
        held.release(SnapshotKey.Platform)
        assertThrows<ExecutionException> { wait.get(10, TimeUnit.SECONDS) }.cause.assert()
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("closed")

        val failing = store().apply { hold(SnapshotKey.Platform) }
        DocumentSnapshots.source(failing, compiler, SnapshotOptions()).use { source ->
            val starting = source.settled(listOf(tenant))
            source.afterSlotLookup = { throw IllegalStateException("no slot") }
            failing.release(SnapshotKey.Platform)
            assertThrows<ExecutionException> { starting.get(10, TimeUnit.SECONDS) }.cause.assert()
                .hasMessage("no slot")
        }
    }

    /** `CND-5` (RC1-21): the store form's compiler refuses a condition on the host's own API key header. */
    @Test
    fun `a document reading the host's API key header does not install`() = runBlocking<Unit> {
        val store = store().apply { inner.put(tenant, document(READS_CUSTOM_KEY)) }
        val custom = Pipeline.builder(authenticator(authenticated()), store)
            .options { apiKeyHeader = "X-Custom-Key" }
            .snapshots { prewarmTenants = setOf(TENANT.value) }
            .build()
        custom.use { pipeline ->
            pipeline.awaitDocuments().assert().containsExactly(tenant)
            pipeline.evaluate(facts()).status.assert().isEqualTo(503)
        }
        // With the default header, the same document is an ordinary condition on another header.
        pipeline(store).use { it.awaitDocuments().assert().isEmpty() }
    }

    @Test
    fun `two inputs that say the same thing differently are refused, not one of them dropped`() {
        val listener = object : DecisionListener {
            override val name = "audit"

            override suspend fun onDecision(event: DecisionEvent) = Unit
        }
        val provider = object : AttributeProvider {
            override val attributes = emptyList<AttributeDeclaration>()

            override suspend fun fetch(request: AttributeRequest, names: Set<String>) = emptyMap<String, Any>()
        }
        fun builder() = Pipeline.builder(authenticator(authenticated()), SuspendingSnapshotSource(InstalledViews()))
        listOf(
            builder().listener(listener).listeners(DecisionListeners.NONE),
            builder().listeners { queueCapacity = 1 }.listeners(DecisionListeners.NONE),
            builder().attributes(provider).attributes(AttributeProviders.NONE),
            builder().options { allowQueryToken = true }.options(PipelineOptions()),
            builder().forwardIdentity { }.identity(IdentityIssuer(emptySet())),
        ).forEach { contradicting ->
            assertThrows<IllegalStateException> { contradicting.build() }
        }
        builder().listener(listener).listeners { queueCapacity = 1 }.build().close()
    }

    /** RFC 0009 §5 through the builder: what `forwardIdentity { }` forwards, `Pipeline.behindGateway { }` accepts. */
    @Test
    fun `the identity a gateway pipeline forwards is accepted by the gate behind it`() = runBlocking<Unit> {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        // Only a principal whose credential is known is forwarded, as a verified token's is.
        val principal = AuthenticatedPrincipal(
            PipelineFixtures.PRINCIPAL,
            TENANT,
            MembershipSource.Claims(PipelineFixtures.claims("" to "clerk")),
            emptyMap(),
            Identity.Credential(Identity.Method.JWT, "t1", now.plusSeconds(3600)),
            breakGlass = true,
            issuedAt = null,
            apiKeyCredential = false,
            app = null,
            session = null,
        )
        val gateway = PipelineFixtures.pipeline(authenticator(AuthnResult.Authenticated(principal, "token"))) {
            forwardIdentity { maxBytes = IdentityCodec.DEFAULT_MAX_BYTES }
            clock(clock)
        }
        val routed = facts().let {
            HttpFacts(
                it.method,
                it.rawPath,
                null,
                Headers.EMPTY,
                Cookies.EMPTY,
                it.remoteAddress,
                it.host,
                requestAttributes = mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "true")
            )
        }
        val header = gateway.evaluate(routed).forward.getValue(IdentityCodec.HEADER)!!
        val behind = Pipeline.behindGateway {
            this.clock = clock
            maxAge = Duration.ofMinutes(1)
            ids = IdGenerator { "behind" }
        }
        val accepted = behind.evaluate(
            HttpFacts(
                "GET",
                "/orders",
                null,
                Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
                Cookies.EMPTY,
                "10.0.0.2",
                HostScope(null)
            )
        )
        accepted.proceed.assert().isTrue()
        accepted.security!!.securityContext.principal.id.assert().isEqualTo(PipelineFixtures.PRINCIPAL.value)
        // A request with no forwarded identity is not let through as anyone.
        behind.evaluate(facts()).security?.securityContext?.principal?.id
            .assert().isNotEqualTo(PipelineFixtures.PRINCIPAL.value)
    }

    @Test
    fun `closing a pipeline over a store delivers the queued events, then closes the documents`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<DecisionEvent>()
        val listener = object : DecisionListener {
            override val name = "recording"

            override suspend fun onDecision(event: DecisionEvent) {
                events += event
            }
        }
        val pipeline = pipeline(store(), listener)
        pipeline.awaitDocuments().assert().isEmpty()
        pipeline.evaluate(facts()).status.assert().isEqualTo(200)
        pipeline.close()
        events.size.assert().isEqualTo(1)
        pipeline.evaluate(facts()).status.assert().isEqualTo(503)
    }

    @Test
    fun `a pipeline over a source its owner keeps closes nothing of it and answers no readiness`() = runBlocking<Unit> {
        val store = store()
        val compiler = PlatformCompiler()
        DocumentSnapshots.source(store, compiler, SnapshotOptions()).also(compiler::bind).use { source ->
            source.load().join()
            val pipeline = Pipeline.builder(
                authenticator(authenticated()),
                SuspendingSnapshotSource(DocumentSnapshotViews(source, platformRequired = true)),
            ).build()
            assertThrows<IllegalStateException> { runBlocking { pipeline.awaitDocuments() } }
            pipeline.evaluate(facts()).status.assert().isEqualTo(200)
            pipeline.close()
            source.peek().assert().isNotNull()
            pipeline.evaluate(facts()).status.assert().isEqualTo(200)
        }
    }

    private companion object {
        const val READS_CUSTOM_KEY = ""","policies":[{"id":"t","statements":[{"effect":"allow","action":"*",""" +
            """"condition":{"prefix":["http.header.X-Custom-Key","ak_"]}}]}]"""
        const val ALLOW_EVERYONE = ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}],""" +
            """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
    }
}
