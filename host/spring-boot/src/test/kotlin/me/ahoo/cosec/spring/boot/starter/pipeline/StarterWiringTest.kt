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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.cache.CoCacheMembershipProvider
import me.ahoo.cosec.cache.MembershipCache
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.kafka.KafkaAuditListener
import me.ahoo.cosec.kafka.KafkaChangeListener
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistKind
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.attribute.InMemoryBlacklistSource
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.control.ControlMaintenanceScheduler
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.Order
import org.springframework.kafka.core.KafkaOperations
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** What the starter registers from the application's own beans: the blacklist, audit sinks, memberships, listeners. */
class StarterWiringTest {
    private val documents = InMemorySnapshotDocumentStore().apply {
        put(
            SnapshotKey.Platform,
            SnapshotDocument(
                SnapshotDocument.DOCUMENT_SCHEMA,
                1,
                Instant.parse(PUBLISHED_AT),
                """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED_AT"$PLATFORM}""".encodeToByteArray(),
            ),
        )
    }

    private val pipelineRunner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("${JwtProperties.PREFIX}.secret=$SECRET", "cosec.authorization.routes.location=")
        .withBean(TokenStore::class.java, { TokenStore.NoOp })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })
        .withBean(SnapshotDocumentStore::class.java, { documents })

    /** [principal]'s token; with [provider], one that asks the membership provider (RFC 0004 §4.5.2). */
    private fun token(context: ApplicationContext, principal: String, provider: Boolean = false): String {
        val plain: CoSecPrincipal = SimplePrincipal(principal)
        val marked = if (provider) MembershipMarkedPrincipal(plain, MembershipClaims.PROVIDER) else plain
        return context.getBean(TokenConverter::class.java)
            .toToken(SimpleTenantPrincipal(marked, SimpleTenant(TENANT)))
            .accessToken
    }

    private fun status(context: ApplicationContext, token: String): Int {
        val facts = HttpFacts(
            method = "GET",
            rawPath = "/orders/1",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf(token))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        return runBlocking { context.getBean(Pipeline::class.java).evaluate(facts).status }
    }

    @Test
    fun `a blacklisted principal is denied through the starter`() {
        val blacklist = InMemoryBlacklistSource().apply {
            block(BlacklistKind.PRINCIPAL, BlacklistKind.principal(TENANT, "alice"))
        }
        pipelineRunner.withBean(BlacklistSource::class.java, { blacklist }).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(BlacklistAttributeProvider::class.java).assert().isNotNull()
            status(context, token(context, "alice")).assert().isEqualTo(403)
            status(context, token(context, "bob")).assert().isEqualTo(200)
        }
    }

    @Test
    fun `a blacklist source that fails makes the request a 503`() {
        val failing = BlacklistSource { _, _ -> error("The blacklist store is down") }
        pipelineRunner.withBean(BlacklistSource::class.java, { failing }).run { context ->
            status(context, token(context, "bob")).assert().isEqualTo(503)
        }
    }

    @Test
    fun `without a blacklist source there is no blacklist provider`() {
        pipelineRunner.run { context ->
            context.getBeansOfType(BlacklistAttributeProvider::class.java).assert().isEmpty()
            status(context, token(context, "alice")).assert().isEqualTo(200)
        }
    }

    @Test
    fun `a plain audit event sink receives every decision`() {
        val events = CopyOnWriteArrayList<AuditEvent>()
        pipelineRunner.withBean("orderAudit", AuditEventSink::class.java, { AuditEventSink { events += it } })
            .run { context ->
                context.startupFailure.assert().isNull()
                status(context, token(context, "bob")).assert().isEqualTo(200)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (events.isEmpty() && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                events.size.assert().isEqualTo(1)
            }
    }

    @Order(2)
    private class Listening : DecisionListener, AuditEventSink {
        val events = AtomicInteger()
        override val name: String = "listening"

        override suspend fun onDecision(event: DecisionEvent) {
            events.incrementAndGet()
        }

        override fun publish(event: AuditEvent) {
            events.incrementAndGet()
        }
    }

    @Order(1)
    private class FirstSink : AuditEventSink {
        override fun publish(event: AuditEvent) = Unit
    }

    @Test
    fun `a sink that is also a listener is registered once, and listeners keep their order`() {
        val listening = Listening()
        pipelineRunner.withBean("listening", Listening::class.java, { listening })
            .withBean("firstSink", FirstSink::class.java, { FirstSink() })
            .run { context ->
                context.startupFailure.assert().isNull()
                val names = context.getBean(DecisionListeners::class.java).stats.map { it.listener }
                names.filter { it == "listening" || it == "firstSink" }.assert().containsExactly(
                    "firstSink",
                    "listening"
                )
                status(context, token(context, "bob")).assert().isEqualTo(200)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (listening.events.get() == 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                Thread.sleep(100)
                listening.events.get().assert().isEqualTo(1)
            }
    }

    private val kafkaRunner = pipelineRunner
        .withBean(KafkaOperations::class.java, { mockk<KafkaOperations<String, String>>(relaxed = true) })

    @Test
    fun `a required Kafka audit records every decision`() {
        kafkaRunner.withPropertyValues("cosec.audit.kafka.delivery=required").run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(KafkaAuditListener::class.java).delivery.assert().isEqualTo(Delivery.REQUIRED)
        }
    }

    /** STA-1: a REQUIRED audit that was not created fails startup instead of deciding without it. */
    @Test
    fun `a required Kafka audit that cannot be created fails startup`() {
        listOf(
            pipelineRunner.withPropertyValues("cosec.audit.kafka.delivery=required"),
            kafkaRunner.withPropertyValues("cosec.audit.kafka.delivery=required", "cosec.audit.enabled=false"),
            kafkaRunner.withPropertyValues("cosec.audit.kafka.delivery=required", "cosec.audit.kafka.enabled=false"),
            pipelineRunner.withPropertyValues("cosec.audit.kafka.delivery= required "),
        ).forEach { runner ->
            runner.run { context ->
                context.startupFailure!!.rootCause().message.assert()
                    .contains("cosec.audit.kafka.delivery=required, but no Kafka audit listener was created")
            }
        }
    }

    @Test
    fun `a Kafka audit delivery that is neither required nor best-effort fails startup`() {
        kafkaRunner.withPropertyValues("cosec.audit.kafka.delivery=sometimes").run { context ->
            context.startupFailure!!.rootCause().message.assert()
                .contains("cosec.audit.kafka.delivery=[sometimes] is neither required nor best-effort")
        }
    }

    private val membershipRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("cocache.backend=memory")
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    @Test
    fun `a membership loader is cached for every instance`() {
        val loads = AtomicInteger()
        val loader = MembershipLoader { _, _ ->
            loads.incrementAndGet()
            MembershipAnswer.Known(Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
        }
        membershipRunner.withBean(MembershipLoader::class.java, { loader }).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(MembershipCache::class.java).assert().isNotNull()
            val provider = context.getBean(MembershipProvider::class.java)
            provider.assert().isInstanceOf(CoCacheMembershipProvider::class.java)
            runBlocking {
                repeat(2) { provider.memberships(TenantId(TENANT), PrincipalId("alice")) }
            }
            loads.get().assert().isEqualTo(1)
        }
        membershipRunner.run { context ->
            context.getBeansOfType(MembershipProvider::class.java).assert().isEmpty()
        }
    }

    private val cachedPipelineRunner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            // The in-memory backend has no maxmemory-policy to read (SNP-3).
            "cosec.authorization.snapshot.eviction-check=warn",
            "${JwtProperties.PREFIX}.secret=$SECRET",
            "cosec.authorization.routes.location=",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withBean(MembershipLoader::class.java, {
            MembershipLoader { _, principal ->
                val roles = if (principal.value == "alice") setOf(RoleId("clerk")) else emptySet()
                MembershipAnswer.Known(
                    Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId("") to roles), emptyList())
                )
            }
        })

    /**
     * Publishes an empty platform document and [CLERKS] as acme's (a role binding is a tenant's or an app's, never the
     * platform's), and waits until the pipeline decides on them.
     */
    private fun published(context: ApplicationContext) {
        val cache = context.getBean(PolicySnapshotCache::class.java)
        val documents = context.getBean(RedisSnapshotDocumentStore::class.java)
        mapOf(SnapshotKey.Platform to "", SnapshotKey.Tenant(TENANT) to CLERKS).forEach { (key, body) ->
            cache.put(
                key,
                SnapshotDocument(
                    SnapshotDocument.DOCUMENT_SCHEMA,
                    1,
                    Instant.parse(PUBLISHED_AT),
                    """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED_AT"$body}""".encodeToByteArray(),
                ),
            )
            documents.notifyChanged(key)
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (status(context, token(context, "bob")) == 503 && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `a provider-marked token is decided with the loader's memberships`() {
        cachedPipelineRunner.run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            status(context, token(context, "alice", provider = true)).assert().isEqualTo(200)
            status(context, token(context, "bob", provider = true)).assert().isEqualTo(403)
        }
    }

    @Test
    fun `memberships that cannot be read make the request a 503`() {
        val async = mockk<AsyncCache<String, Memberships>>()
        every { async.get(any()) } returns
            CompletableFuture.failedFuture<Memberships>(IllegalStateException("Redis is down"))
        val failing = mockk<MembershipCache>()
        every { failing.async() } returns async
        cachedPipelineRunner.withBean(MembershipCache::class.java, { failing }).run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            status(context, token(context, "alice", provider = true)).assert().isEqualTo(503)
        }
    }

    private val controlRunner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            // The in-memory backend has no maxmemory-policy to read (SNP-3).
            "cosec.authorization.snapshot.eviction-check=warn",
            "${JwtProperties.PREFIX}.secret=$SECRET",
            "cosec.authorization.routes.location=",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
    private val kafkaControlRunner = controlRunner
        .withBean(KafkaOperations::class.java, { mockk<KafkaOperations<String, String>>(relaxed = true) })

    private val ownListener = object : ChangeListener {
        override val name: String = "own"

        override suspend fun onChange(event: ChangeEvent) = Unit
    }

    @Test
    fun `change listeners add up, and Kafka records changes under its toggle`() {
        kafkaControlRunner.withBean("ownChangeListener", ChangeListener::class.java, { ownListener })
            .withPropertyValues("cosec.control.audit.kafka.delivery=required")
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(ChangeListener::class.java).keys.assert()
                    .containsExactlyInAnyOrder(
                        "ownChangeListener",
                        "cosecLoggingChangeListener",
                        "cosecKafkaChangeListener"
                    )
                context.getBean(KafkaChangeListener::class.java).delivery.assert().isEqualTo(Delivery.REQUIRED)
                context.getBean(ControlMaintenanceScheduler::class.java).isRunning.assert().isTrue()
            }
        kafkaControlRunner.withPropertyValues("cosec.control.audit.kafka.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(ChangeListener::class.java).keys.assert()
                .containsExactly("cosecLoggingChangeListener")
        }
    }

    @Test
    fun `a required Kafka change audit that cannot be created fails startup`() {
        controlRunner.withPropertyValues("cosec.control.audit.kafka.delivery=required").run { context ->
            context.startupFailure!!.rootCause().message.assert().contains("delivery=required")
        }
        kafkaControlRunner.withPropertyValues(
            "cosec.control.audit.kafka.delivery=required",
            "cosec.control.audit.kafka.enabled=false",
        ).run { context ->
            context.startupFailure.assert().isNotNull()
        }
    }

    @Test
    fun `change listeners take the required timeout and stop with the context`() {
        lateinit var listeners: ChangeListeners
        controlRunner.withPropertyValues("cosec.control.audit.required-timeout=7s").run { context ->
            context.startupFailure.assert().isNull()
            listeners = context.getBean(ChangeListeners::class.java)
            listeners.requiredTimeout.assert().isEqualTo(7.seconds)
        }
        (ReflectionTestUtils.getField(listeners, "scope") as CoroutineScope).isActive.assert().isFalse()
    }

    /**
     * The control plane's own authorization never comes from an application bean (RFC 0011 D4): a second authorizer,
     * change authority or control plane fails startup instead of quietly replacing the starter's.
     */
    @Test
    fun `a second control-plane authorization bean fails startup`() {
        val authorizer = ControlAuthorizer { _, _, _ -> ControlDecision.DENY }
        secondBeanFailsStartup(ControlAuthorizer::class.java) { authorizer }
        secondBeanFailsStartup(ChangeAuthority::class.java) { ChangeAuthority(authorizer) }
        secondBeanFailsStartup(ControlPlane::class.java) { mockk<ControlPlane>() }
    }

    private fun <T : Any> secondBeanFailsStartup(type: Class<T>, bean: () -> T) {
        controlRunner.withBean("own${type.simpleName}", type, { bean() }).run { context ->
            val cause = context.startupFailure!!.rootCause()
            cause.assert().isInstanceOf(NoUniqueBeanDefinitionException::class.java)
            cause.message.assert().contains(type.name)
        }
    }

    private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

    private companion object {
        /** acme's clerks, in the default space, may do anything. */
        const val CLERKS = ""","policies":[{"id":"clerks","statements":[{"effect":"allow","action":"http:*"}]}],""" +
            """"bindings":[{"id":"clerks","subject":{"role":"clerk"},"app":"*","space":"*","policy":"clerks"}]"""

        const val TENANT = "acme"
        const val SECRET = "FyN0Igd80Gas8stTavArGKOYnS9uLwGA_"
        const val PUBLISHED_AT = "2026-10-28T00:00:00Z"
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"}]"""
    }
}
