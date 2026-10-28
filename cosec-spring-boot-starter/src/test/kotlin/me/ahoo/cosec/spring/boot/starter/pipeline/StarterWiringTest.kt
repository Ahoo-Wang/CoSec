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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.cache.CoCacheMembershipProvider
import me.ahoo.cosec.cache.MembershipCache
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.kafka.KafkaChangeListener
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistKind
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.attribute.InMemoryBlacklistSource
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.control.ControlMaintenanceScheduler
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.RevocationReader
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosec.token.TokenStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.KafkaOperations
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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

    private fun token(context: ApplicationContext, principal: String): String =
        context.getBean(TokenConverter::class.java)
            .toToken(SimpleTenantPrincipal(SimplePrincipal(principal), SimpleTenant(TENANT)))
            .accessToken

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
            Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
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
        .withBean(KafkaOperations::class.java, { mockk<KafkaOperations<String, String>>(relaxed = true) })

    private val ownListener = object : ChangeListener {
        override val name: String = "own"

        override suspend fun onChange(event: ChangeEvent) = Unit
    }

    @Test
    fun `change listeners add up, and Kafka records changes under its toggle`() {
        controlRunner.withBean("ownChangeListener", ChangeListener::class.java, { ownListener })
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
        controlRunner.withPropertyValues("cosec.control.audit.kafka.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(ChangeListener::class.java).keys.assert()
                .containsExactly("cosecLoggingChangeListener")
        }
    }

    private companion object {
        const val TENANT = "acme"
        const val SECRET = "FyN0Igd80Gas8stTavArGKOYnS9uLwGA_"
        const val PUBLISHED_AT = "2026-10-28T00:00:00Z"
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"}]"""
    }
}
