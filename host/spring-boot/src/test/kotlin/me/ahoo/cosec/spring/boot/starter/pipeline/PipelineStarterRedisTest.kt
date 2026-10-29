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

package me.ahoo.cosec.spring.boot.starter.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.cache.shadow.CoCacheShadowReports
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.change.ShadowWindows
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlRoutesAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The 8.0 path as the starter wires it, end to end on real Redis (RFC 0008 S1): a WebFlux host, the policy snapshot
 * from Redis, JWTs checked against the CoCache revocation reader, and the control plane's routes behind the pipeline.
 */
class PipelineStarterRedisTest {
    private val keyPrefix = "s1-${UUID.randomUUID()}"

    private val runner = ReactiveWebApplicationContextRunner()
        // A Spring Cloud Gateway host gets the gateway filter instead; this one is plain WebFlux.
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
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
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.cache.key-prefix=$keyPrefix",
            "cosec.authorization.snapshot.key-prefix=$keyPrefix",
            "cosec.authorization.snapshot.revalidate-interval=200ms",
            "cosec.authorization.routes.location=",
        )
        .withBean("orders", RouterFunction::class.java, {
            RouterFunctions.route().GET("/orders/{id}") { ServerResponse.ok().bodyValue("ok") }.build()
        })

    private fun client(context: ApplicationContext): WebTestClient =
        WebTestClient.bindToApplicationContext(
            context
        ).configureClient().responseTimeout(Duration.ofSeconds(10)).build()

    private fun token(context: ApplicationContext, tenant: String, id: String): String =
        context.getBean(TokenConverter::class.java)
            .toToken(SimpleTenantPrincipal(SimplePrincipal(id), SimpleTenant(tenant)))
            .accessToken

    private fun publish(context: ApplicationContext, key: SnapshotKey, kind: DocumentKind, owner: String, body: String) {
        val revisions = context.getBean(Revisions::class.java)
        runBlocking {
            val head = revisions.head(key)
            val revision = (head?.revision ?: 0) + 1
            val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-27T00:00:00Z"$body}"""
            revisions.publish(
                key,
                head,
                PolicyDocumentCodec.decode(kind, owner, text.encodeToByteArray()),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
    }

    /** Polls until the instance has installed what was published: publishing never waits for propagation. */
    private fun eventually(block: () -> Unit) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (true) {
            try {
                block()
                return
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    private fun WebTestClient.status(path: String, token: String? = null, method: String = "GET", body: String? = null): Int {
        val spec = method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
        token?.let { spec.header(HttpHeaders.AUTHORIZATION, it) }
        body?.let { spec.header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(it) }
        return spec.exchange().returnResult(ByteArray::class.java).status.value()
    }

    @Test
    fun `the starter wires the 8_0 path end to end`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(Pipeline::class.java).assert().isNotNull()
            context.getBean(ControlHttp::class.java).assert().isNotNull()
            val client = client(context)
            // No platform document yet: requests fail closed instead of reading an empty platform.
            client.status("/orders/1").assert().isEqualTo(503)

            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            eventually { client.status("/orders/1").assert().isEqualTo(401) }

            // A revoked token, by token and by subject, is 401 token-revoked (#1072).
            val alice = token(context, "acme", "alice")
            client.status("/orders/1", alice).assert().isEqualTo(200)
            context.getBean(TokenRevoker::class.java).revoke(
                me.ahoo.cosec.runtime.token.SimpleAccessToken(alice)
            )
            val revoked = client.get().uri("/orders/1").header(HttpHeaders.AUTHORIZATION, alice)
                .exchange().expectStatus().isEqualTo(401).expectBody(String::class.java).returnResult().responseBody!!
            revoked.assert().contains("token-revoked")
            val bob = token(context, "acme", "bob")
            client.status("/orders/1", bob).assert().isEqualTo(200)
            context.getBean(TokenRevoker::class.java)
                .revokeSubject(TenantId("acme"), PrincipalId("bob"), Instant.now().plusSeconds(60))
            client.status("/orders/1", bob).assert().isEqualTo(401)

            // The control API behind the pipeline: a permitted request, authorized again as its principal.
            val plato = token(context, TenantId.PLATFORM.value, "plato")
            val simulate = """{"request":{"tenant":"acme","principal":"carol","action":"http:GET","resource":"/orders/1"}}"""
            client.status("/cosec/control/v1/simulate", plato, "POST", simulate).assert().isEqualTo(200)
            client.status("/cosec/control/v1/simulate", null, "POST", simulate).assert().isEqualTo(401)
            client.status("/cosec/control/v1/simulate", token(context, "acme", "carol"), "POST", simulate)
                .assert().isEqualTo(403)
        }
    }

    /** N3: an instance document compiles against the platform's limits, and again when they change. */
    @Test
    fun `a platform change recompiles the documents that depend on it`() {
        runner.run { context ->
            val client = client(context)
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            publish(context, SnapshotKey.Tenant("acme"), DocumentKind.TENANT, "acme", TENANT)
            val dave = token(context, "acme", "dave")
            eventually { client.status("/orders/1", dave).assert().isEqualTo(200) }
            client.status("/orders/1", dave, "DELETE").assert().isEqualTo(403)
            // Two nodes per condition: the tenant's DENY no longer compiles, so its requests fail closed.
            publish(
                context,
                SnapshotKey.Platform,
                DocumentKind.PLATFORM,
                "",
                PLATFORM.replace("\"bindings\"", "\"limits\":{\"maxNodes\":2},\"bindings\"")
            )
            eventually { client.status("/orders/1", dave).assert().isEqualTo(503) }
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            eventually { client.status("/orders/1", dave).assert().isEqualTo(200) }
        }
    }

    /** S3: the data plane takes part in shadow windows, so a candidate one instance cannot compile blocks its window. */
    @Test
    fun `a candidate that does not compile on an instance blocks its window`() {
        runner.withPropertyValues(
            "cosec.authorization.shadow.sample-rate=1",
            "cosec.authorization.shadow.report-interval=100ms",
        ).run { context ->
            val client = client(context)
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            val window = "w-${UUID.randomUUID()}"
            val startedAt = Instant.now()
            val uncompilable = """{"schema":2,"revision":2,"publishedAt":"2026-10-27T00:00:00Z","policies":"none"}"""
            runBlocking {
                context.getBean(ControlStore::class.java).putCandidate(
                    SnapshotKey.Platform,
                    StoredCandidate(window, 1, SnapshotDocument(2, 2, startedAt, uncompilable.encodeToByteArray())),
                )
            }
            val windows = ShadowWindows(context.getBean(CoCacheShadowReports::class.java))
            val alice = token(context, "acme", "alice")
            eventually {
                // The enforced decision never notices the candidate.
                client.status("/orders/1", alice).assert().isEqualTo(200)
                val summary = runBlocking { windows.close(ShadowState(window, startedAt), Instant.now()) }
                summary?.blocked.assert().isEqualTo(true)
            }
        }
    }

    /** An instance id ends its report's Redis key, so one with a `:` could name another instance's report. */
    @Test
    fun `an instance id outside letters, digits, dot, underscore and dash fails startup`() {
        runner.withPropertyValues("cosec.authorization.shadow.instance-id=a:b").run { context ->
            context.startupFailure.assert().isNotNull()
        }
        runner.run { context ->
            context.startupFailure.assert().isNull()
            ShadowReport.isInstanceId(context.getBean(ShadowRecorder::class.java).instance).assert().isTrue()
        }
    }

    private companion object {
        /** Authenticated principals may call any HTTP route; the platform admin administers the control plane. */
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
            """{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"","policy":"cosec:platform-admin"}]"""

        /** A DENY with a three-node condition: more than a platform that allows two nodes compiles. */
        const val TENANT = ""","policies":[{"id":"no-delete","statements":[{"effect":"deny","action":"http:DELETE",""" +
            """"condition":{"and":[{"eq":["subject.authenticated",true]},{"exists":"subject.id"}]}}]}],""" +
            """"bindings":[{"id":"no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"}]"""
    }
}
