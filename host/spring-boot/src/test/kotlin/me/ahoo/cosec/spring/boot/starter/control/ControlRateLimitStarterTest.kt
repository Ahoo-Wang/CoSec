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

package me.ahoo.cosec.spring.boot.starter.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * D2, the control API's per-tenant limits, with no mechanism of their own: the control routes are served behind the
 * data-plane pipeline, so a platform rate limit rule on every path under `{base-path}/v1`, keyed by `scope.tenant`, gives
 * each tenant one bucket. An exhausted bucket answers 429 before the request reaches the control routes, and another
 * tenant's bucket is untouched. A tenant cannot change the rule (`OBL-2`).
 */
class ControlRateLimitStarterTest {
    /** The control API requests that passed the pipeline: what `ControlHttp` could do any work for. */
    private val reached = AtomicInteger()

    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                HttpHandlerAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
                CoSecControlRoutesAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            // The in-memory backend has no maxmemory-policy to read (SNP-3).
            "cosec.authorization.snapshot.eviction-check=warn",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean("reached", WebFilter::class.java, {
            object : WebFilter, Ordered {
                override fun filter(
                    exchange: org.springframework.web.server.ServerWebExchange,
                    chain: org.springframework.web.server.WebFilterChain,
                ) = chain.filter(exchange).also {
                    if (exchange.request.path.value().startsWith(CONTROL)) reached.incrementAndGet()
                }

                // After the pipeline's filter: only what it let through.
                override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
            }
        })

    private fun publish(context: ApplicationContext, key: SnapshotKey, kind: DocumentKind, owner: String, body: String) {
        val revisions = context.getBean(Revisions::class.java)
        runBlocking {
            val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}"""
            revisions.publish(
                key,
                revisions.head(key),
                PolicyDocumentCodec.decode(kind, owner, text.encodeToByteArray()),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
    }

    private fun token(context: ApplicationContext, tenant: String, id: String): String =
        context.getBean(TokenConverter::class.java)
            .toToken(SimpleTenantPrincipal(SimplePrincipal(id, roles = setOf("admin")), SimpleTenant(tenant)))
            .accessToken

    private fun WebTestClient.simulate(token: String, tenant: String): Pair<Int, String> {
        val body = """{"request":{"tenant":"$tenant","principal":"bob","action":"order:read","resource":"order/1"}}"""
        val result = post().uri("$CONTROL/simulate")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        return result.status.value() to result.responseBody.orEmpty()
    }

    @Test
    fun `a platform rule keyed by the tenant answers 429 before the control API, one bucket per tenant`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            for (tenant in listOf("acme", "globex")) {
                publish(context, SnapshotKey.Tenant(tenant), DocumentKind.TENANT, tenant, ADMINS)
            }
            val client = WebTestClient.bindToApplicationContext(context).configureClient()
                .responseTimeout(Duration.ofSeconds(10)).build()
            // Startup read no platform document: wait until this instance has installed the one just published, by a
            // request the rule does not cover (permitted, and then no route: 404).
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (client.get().uri("/probe").header(HttpHeaders.AUTHORIZATION, token(context, "acme", "probe"))
                    .exchange().returnResult(String::class.java).status.value() != 404
            ) {
                check(System.nanoTime() < deadline) { "The platform document was not installed." }
                Thread.sleep(20)
            }
            val alice = token(context, "acme", "alice")
            val mallory = token(context, "globex", "mallory")
            repeat(PERMITS) { client.simulate(alice, "acme").first.assert().isEqualTo(200) }
            reached.get().assert().isEqualTo(PERMITS)
            val (status, problem) = client.simulate(alice, "acme")
            status.assert().isEqualTo(429)
            problem.assert().contains("too-many-requests")
            // The pipeline answered it: the control routes never saw the request.
            reached.get().assert().isEqualTo(PERMITS)
            // globex's bucket is its own.
            client.simulate(mallory, "globex").first.assert().isEqualTo(200)
            reached.get().assert().isEqualTo(PERMITS + 1)
        }
    }

    private companion object {
        const val CONTROL = "/cosec/control/v1"

        /** `permitsPerSecond × windowSeconds`: three requests a minute per tenant. */
        const val PERMITS = 3

        /** Any authenticated principal is let in; plato administers; the control API is limited per tenant. */
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
            """{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"",""" +
            """"policy":"cosec:platform-admin"}],""" +
            """"rateLimits":[{"id":"control-per-tenant","action":"http:*","resource":"http:path:$CONTROL/**",""" +
            """"key":"scope.tenant","permitsPerSecond":0.05,"windowSeconds":60}]"""

        /** The tenant's administrators, by role. */
        const val ADMINS = ""","bindings":[{"id":"admins","subject":{"role":"admin"},"app":"cosec-control",""" +
            """"space":"","policy":"cosec:tenant-admin"}]"""
    }
}
