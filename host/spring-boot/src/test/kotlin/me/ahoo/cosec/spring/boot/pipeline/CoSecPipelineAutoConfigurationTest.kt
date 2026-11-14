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

package me.ahoo.cosec.spring.boot.pipeline

import com.auth0.jwt.algorithms.Algorithm
import io.lettuce.core.RedisURI
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.ServletInputStream
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.apikey.CoSecApiKeyAutoConfiguration
import me.ahoo.cosec.spring.boot.control.CoSecControlRoutesAutoConfiguration
import me.ahoo.cosec.spring.boot.control.ControlProperties
import me.ahoo.cosec.spring.boot.control.ControlRoutesWebFilter
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webmvc.SecurityContextArgumentResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.kafka.core.KafkaOperations
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** The 8.0 auto-configuration without Redis: an in-memory snapshot, the default integrations, the startup checks. */
class CoSecPipelineAutoConfigurationTest {
    private val routes: File = Files.createTempFile("cosec-routes", ".json").toFile().apply {
        deleteOnExit()
        writeText(
            """{"service":"order","routes":[""" +
                """{"method":"GET","path":"/orders/{orderId}","action":"order:read","resource":"order/{orderId}"}]}"""
        )
    }

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecJwtAutoConfiguration::class.java,
                CoSecApiKeyAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=file:${routes.absolutePath}",
        )
        // Without Redis an application brings its own revocation store and reader; here, explicitly, none.
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    private fun facts(token: String? = null, cookie: String? = null) = HttpFacts(
        method = "GET",
        rawPath = "/orders/1",
        rawQuery = null,
        headers = Headers.of(token?.let { mapOf("Authorization" to listOf(it)) }.orEmpty()),
        cookies = Cookies.of(cookie?.let { mapOf("authorization" to listOf(it)) }.orEmpty()),
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )

    @Test
    fun `the pipeline is assembled from the starter's defaults`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(
                SnapshotDocumentStore::class.java
            ).assert().isInstanceOf(InMemorySnapshotDocumentStore::class.java)
            context.getBean(RateLimiter::class.java).assert().isInstanceOf(LocalRateLimiter::class.java)
            context.getBean(AuthorizationChecker::class.java).assert().isNotNull()
            context.getBean(DecisionListeners::class.java).assert().isNotNull()
            context.getBean(RouteMap::class.java).assert().isNotSameAs(RouteMap.EMPTY)
            // Nothing is published: no platform document, so the request fails closed.
            runBlocking { context.getBean(Pipeline::class.java).evaluate(facts()).status }.assert().isEqualTo(503)
        }
    }

    @Test
    fun `a route map that does not exist fails startup, and an empty location means none`() {
        runner.withPropertyValues("cosec.authorization.routes.location=classpath:nowhere.json").run { context ->
            context.startupFailure.assert().isNotNull()
            context.startupFailure!!.stackTraceToString().assert().contains("nowhere.json")
        }
        runner.withPropertyValues("cosec.authorization.routes.location=").run { context ->
            context.getBean(RouteMap::class.java).assert().isSameAs(RouteMap.EMPTY)
        }
    }

    @Test
    fun `a gateway loads no route map`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java
                )
            )
            .withPropertyValues("cosec.authorization.routes.location=classpath:nowhere.json")
            .run { context -> context.getBean(RouteMap::class.java).assert().isSameAs(RouteMap.EMPTY) }
    }

    @Test
    fun `tokens without a revocation reader fail startup instead of revoking nothing`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("cosec.authorization.routes.location=")
            .withBean(Algorithm::class.java, { Algorithm.HMAC256("FyN0Igd80Gas8stTavArGKOYnS9uLwGA_") })
            .withBean(TokenStore::class.java, { NoTokenStore })
            .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
            .run { context ->
                context.startupFailure!!.stackTraceToString().assert().contains("no revocation reader")
            }
    }

    @Test
    fun `without a token verifier a token is rejected and no token is anonymous`() {
        runner.withPropertyValues("${JwtProperties.PREFIX}.enabled=false").run { context ->
            val authenticator = context.getBean(Authenticator::class.java)
            runBlocking {
                authenticator.authenticate(
                    Credentials(null, null)
                )
            }.assert().isEqualTo(AuthnResult.Anonymous)
            runBlocking {
                authenticator.authenticate(Credentials("t", null))
            }.assert().isInstanceOf(AuthnResult.Rejected::class.java)
        }
    }

    /** `PIP-13`: a cookie reaches a host on a cross-site request too, so it is a credential only on request. */
    @Test
    fun `the authorization cookie is a credential only with allow-cookie-token`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val recording = runner.withBean(
            Authenticator::class.java,
            {
                Authenticator {
                    seen += it.token
                    AuthnResult.Anonymous
                }
            },
        )
        recording.run { context -> runBlocking { context.getBean(Pipeline::class.java).evaluate(facts(cookie = "c")) } }
        recording.withPropertyValues("cosec.pipeline.allow-cookie-token=true").run { context ->
            runBlocking { context.getBean(Pipeline::class.java).evaluate(facts(cookie = "c")) }
        }
        seen.assert().containsExactly(null, "c")
    }

    @Test
    fun `api keys come from the in-memory store, or startup fails`() {
        val keys = runner.withPropertyValues("cosec.api-key.enabled=true")
            .withBean(MembershipProvider::class.java, { InMemoryMembershipProvider() })
        keys.run { context ->
            context.startupFailure.assert().isNull()
            runBlocking {
                context.getBean(Authenticator::class.java).authenticate(Credentials(null, "unknown"))
            }.assert().isInstanceOf(AuthnResult.Rejected::class.java)
        }
        keys.withBean(ApiKeyStore::class.java, { ApiKeyStore { null } })
            .run { context -> context.startupFailure!!.stackTraceToString().assert().contains("no ApiKeyReader") }
    }

    @Test
    fun `a servlet host gets the pipeline filter`() {
        WebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecPipelineWebAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("cosec.authorization.routes.location=", "cosec.authorization.scope.app-id=order")
            .run { context -> context.getBean(FilterRegistrationBean::class.java).filter.assert().isNotNull() }
    }

    /**
     * Round 2 M4: Micrometer's context propagation is optional. Without it a servlet host starts, with the argument
     * resolver and the interceptor, and nothing loads the accessor that implements its interface.
     */
    @Test
    fun `a servlet host starts without context propagation`() {
        WebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER, "io.micrometer.context"))
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecPipelineWebAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("cosec.authorization.routes.location=", "cosec.authorization.scope.app-id=order")
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(SecurityContextArgumentResolver::class.java).assert().isNotNull()
            }
    }

    @Test
    fun `a WebFlux host gets the pipeline web filter, a gateway host the gateway filter`() {
        val web = AutoConfigurations.of(
            CoSecSnapshotAutoConfiguration::class.java,
            CoSecPipelineAutoConfiguration::class.java,
            CoSecPipelineWebAutoConfiguration::class.java,
        )
        ReactiveWebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(web)
            .withPropertyValues(
                "cosec.authorization.routes.location=",
                "cosec.authorization.remote-ip.max-trusted-index=0"
            )
            .run { context ->
                context.getBeansOfType(PipelineWebFilter::class.java).assert().hasSize(1)
                context.getBeansOfType(PipelineGatewayFilter::class.java).assert().isEmpty()
            }
        ReactiveWebApplicationContextRunner()
            .withConfiguration(web)
            .run { context ->
                context.getBeansOfType(PipelineGatewayFilter::class.java).assert().hasSize(1)
                context.getBeansOfType(PipelineWebFilter::class.java).assert().isEmpty()
            }
    }

    /**
     * RC1-50 (`SNP-6`): the periodic revalidation is the only bound on a lost invalidation, so an interval that turns
     * it off or leaves it unbounded fails startup, naming the property: zero, a negative one, one under a second, over
     * 5 minutes, at or over `lag-warn`, and at or over `fail-closed.max-lag` with fail-closed tenants.
     */
    @Test
    fun `a revalidate-interval that turns off or unbinds the periodic pass fails startup`() {
        val property = "cosec.authorization.snapshot.revalidate-interval"
        for (values in listOf(
            listOf("$property=0"),
            listOf("$property=-1s"),
            // Below the floor of a second (review L1 of #1200).
            listOf("$property=500us"),
            listOf("$property=1ms"),
            listOf(
                "$property=6m",
                "cosec.authorization.snapshot.lag-warn=10m",
                "cosec.authorization.snapshot.lag-escalate=10m"
            ),
            listOf("$property=60s"),
            listOf(
                "$property=30s",
                "cosec.authorization.snapshot.fail-closed.tenants=strict",
                "cosec.authorization.snapshot.fail-closed.max-lag=30s",
            ),
        )) {
            runner.withPropertyValues(*values.toTypedArray()).run { context ->
                context.startupFailure.assert().describedAs(values.first()).isNotNull()
                context.startupFailure!!.stackTraceToString().assert().contains(property)
            }
        }
        runner.withPropertyValues(
            "$property=5m",
            "cosec.authorization.snapshot.lag-warn=6m",
            "cosec.authorization.snapshot.lag-escalate=10m",
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `Redis that may evict snapshots fails startup unless told to warn`() {
        val evicting = EvictionPolicyCheck.Reading.Policies(mapOf("node" to "allkeys-lru"))
        assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(evicting, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }
        assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(
                EvictionPolicyCheck.Reading.Policies(emptyMap()),
                RuntimeAuthorizationProperties.EvictionCheck.FAIL
            )
        }
        EvictionPolicyCheck.check(evicting, RuntimeAuthorizationProperties.EvictionCheck.WARN)
        EvictionPolicyCheck.check(
            EvictionPolicyCheck.Reading.Policies(mapOf("node" to "noeviction")),
            RuntimeAuthorizationProperties.EvictionCheck.FAIL
        )
        EvictionPolicyCheck.check(
            EvictionPolicyCheck.Reading.NotRedis,
            RuntimeAuthorizationProperties.EvictionCheck.FAIL
        )
    }

    @Test
    fun `a maxmemory-policy that cannot be read fails startup unless told to warn`() {
        val redis = mockk<RedisBackend> { every { maxmemoryPolicies() } returns null }
        val unreadable = EvictionPolicyCheck.read(redis)
        unreadable.assert().isInstanceOf(EvictionPolicyCheck.Reading.Unreadable::class.java)
        assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(unreadable, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }
        EvictionPolicyCheck.check(unreadable, RuntimeAuthorizationProperties.EvictionCheck.WARN)
        // Review L3 of #1240: a cluster connection that knows no upstream node reads no policy either.
        val nodeless = EvictionPolicyCheck.read(
            mockk<RedisBackend> { every { maxmemoryPolicies() } returns emptyMap() }
        )
        nodeless.assert().isEqualTo(EvictionPolicyCheck.Reading.Unreadable("no Redis node answered INFO memory"))
        assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(nodeless, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }.message.assert().contains("Cannot tell")
        // A custom backend that unwraps to neither of CoCache's is not known to keep the snapshot either.
        val custom = mockk<CoherenceBackend> {
            every { unwrap(RedisBackend::class.java) } returns null
            every { unwrap(InMemoryBackend::class.java) } returns null
        }
        EvictionPolicyCheck.read(custom).assert().isInstanceOf(EvictionPolicyCheck.Reading.Unreadable::class.java)
        // A wrapper is read through CoherenceBackend.unwrap (CoCache 8.0.0-rc.1): what it wraps decides.
        EvictionPolicyCheck.read(
            HookedBackend(InMemoryBackend())
        ).assert().isEqualTo(EvictionPolicyCheck.Reading.NotRedis)
        RedisBackend.standalone(RedisURI.create("redis://localhost:6379")).use { redis ->
            val reading = EvictionPolicyCheck.read(HookedBackend(redis))
            reading.assert().isInstanceOf(EvictionPolicyCheck.Reading.Policies::class.java)
            (reading as EvictionPolicyCheck.Reading.Policies).policies.assert().isEqualTo(redis.maxmemoryPolicies())
        }
        EvictionPolicyCheck.read(
            InMemoryBackend()
        ).assert().isEqualTo(EvictionPolicyCheck.Reading.NotRedis)
        EvictionPolicyCheck.read(
            mockk<RedisBackend> { every { maxmemoryPolicies() } returns mapOf("n" to "noeviction") }
        )
            .assert().isEqualTo(EvictionPolicyCheck.Reading.Policies(mapOf("n" to "noeviction")))
    }

    @Test
    fun `the WebMvc control routes need a permitted request`() {
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc()
            .cosecControlRoutes(ControlHttp(mockk<ControlPlane>()), ControlProperties())
        MockMvcBuilders.routerFunctions(routes).build()
            .perform(MockMvcRequestBuilders.get("/cosec/control/v1/documents/platform"))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized)
            .andExpect(MockMvcResultMatchers.content().contentType("application/problem+json"))
    }

    /** RFC 0010 C2: the version is fixed under `base-path`, so moving the API keeps `/v1`. */
    @Test
    fun `a moved control API keeps its version`() {
        val properties = ControlProperties().apply { http.basePath = "/admin/cosec" }
        CoSecControlRoutesAutoConfiguration.apiRootOf(properties).assert().isEqualTo("/admin/cosec/v1")
        CoSecControlRoutesAutoConfiguration.apiRootOf(ControlProperties()).assert().isEqualTo("/cosec/control/v1")
        val mvc = MockMvcBuilders.routerFunctions(
            CoSecControlRoutesAutoConfiguration.WebMvc().cosecControlRoutes(
                ControlHttp(mockk<ControlPlane>()),
                properties
            )
        ).build()
        mvc.perform(MockMvcRequestBuilders.get("/admin/cosec/v1/documents/platform"))
            .andExpect(MockMvcResultMatchers.status().isUnauthorized)
        mvc.perform(MockMvcRequestBuilders.get("/admin/cosec/documents/platform"))
            .andExpect(MockMvcResultMatchers.status().isNotFound)
    }

    @Test
    fun `the WebMvc control routes read no body before a permitted request, and no more than the cap`() {
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc()
            .cosecControlRoutes(ControlHttp(mockk<ControlPlane>()), ControlProperties())
        val unread = RequestBuilder { context ->
            object : MockHttpServletRequest(context, "POST", "/cosec/control/v1/changes") {
                override fun getInputStream(): ServletInputStream = throw AssertionError("The body was read.")
            }
        }
        MockMvcBuilders.routerFunctions(routes).build()
            .perform(unread)
            .andExpect(MockMvcResultMatchers.status().isUnauthorized)
        CoSecControlRoutesAutoConfiguration.readBody(
            ByteArrayInputStream(ByteArray(16)),
            16
        )!!.size.assert().isEqualTo(16)
        CoSecControlRoutesAutoConfiguration.readBody(ByteArrayInputStream(ByteArray(17)), 16).assert().isNull()
        CoSecControlRoutesAutoConfiguration.tooLarge(16).statusCode().value().assert().isEqualTo(400)
        ControlProperties().http.maxBodySize.toMegabytes().assert().isEqualTo(16)
    }

    @Test
    fun `on a gateway host the pipeline covers the control routes and nothing else`() {
        val pipeline = mockk<PipelineWebFilter> {
            every { order } returns 1
            every { filter(any(), any()) } returns Mono.empty()
        }
        val filter = ControlRoutesWebFilter(pipeline, "/cosec/control/v1")
        val chain = mockk<WebFilterChain> { every { filter(any()) } returns Mono.empty() }
        for (path in listOf("/cosec/control/v1", "/cosec/control/v1/changes")) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(path)), chain).block()
        }
        for (path in listOf("/orders/1", "/cosec/control/v10")) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(path)), chain).block()
        }
        verify(exactly = 2) { pipeline.filter(any(), any()) }
        verify(exactly = 2) { chain.filter(any()) }
        filter.order.assert().isEqualTo(1)
    }

    @Test
    fun `the optional features add their listeners and attribute providers`() {
        runner.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(KafkaOperations::class.java, { mockk<KafkaOperations<String, String>>(relaxed = true) })
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(DecisionListener::class.java).keys.assert().contains(
                    "cosecLoggingAuditListener",
                    "cosecKafkaAuditListener",
                    "cosecMetricsListener",
                    "cosecTracingListener",
                )
                context.getBean(AttributeProviders::class.java).catalog.external("ext.ip2region.country")
                    .assert().isNotNull()
                context.getBeansOfType(BridgeContextContributor::class.java).size.assert().isEqualTo(2)
            }
    }
}
