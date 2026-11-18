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

package me.ahoo.cosec.spring.boot.realserver

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.cors.CorsConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/** The stacks the served login routes are guarded on: Spring MVC on a real Tomcat, WebFlux on a real Reactor Netty. */
enum class ServedStack(val type: WebApplicationType, val hidden: List<String>) {
    TOMCAT(WebApplicationType.SERVLET, listOf("org.eclipse.jetty", "io.undertow")),
    NETTY(
        WebApplicationType.REACTIVE,
        listOf("org.eclipse.jetty", "io.undertow", "org.apache.catalina", "org.apache.coyote")
    ),
}

/**
 * A whole application on [stack], the starter's own wiring over CoCache in memory, serving the login routes at
 * [REFRESH] and [LOGOUT], with [document] published as its platform document. The session store, the revocation reads
 * and the membership refresh can be made to fail, each until turned back. [extra] adds the application's own beans.
 */
class ServedLoginRouteHost(
    stack: ServedStack,
    document: String,
    vararg extra: Class<*>,
) : AutoCloseable {
    /** A switch: on, every call fails as an unreachable store does. */
    class Outage {
        @Volatile
        var on = false
    }

    class SwitchableRevocations(private val reader: RevocationReader, private val outage: Outage) : RevocationReader {
        /** The ids of the tokens whose revocation read failed while the switch was on. */
        val failedReads: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        override suspend fun isRevoked(tokenId: String): Boolean {
            if (outage.on) {
                failedReads += tokenId
                throw RevocationUnavailableException("revocations are down")
            }
            return reader.isRevoked(tokenId)
        }

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? {
            if (outage.on) throw RevocationUnavailableException("revocations are down")
            return reader.subjectRevokedBefore(tenant, principal)
        }
    }

    class SwitchableFamilies(private val store: TokenFamilyStore, private val outage: Outage) : TokenFamilyStore by store {
        override suspend fun read(id: String): TokenFamilyStore.Read? {
            check(!outage.on) { "the family store is down" }
            return store.read(id)
        }

        override suspend fun replace(version: String, next: TokenFamily): Boolean {
            check(!outage.on) { "the family store is down" }
            return store.replace(version, next)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class App {
        @Bean
        fun revocationOutage() = Outage()

        @Bean
        fun familyOutage() = Outage()

        @Bean
        fun membershipBug() = Outage()

        @Bean
        fun switchableRevocations(tokenStore: TokenStore, revocationOutage: Outage): SwitchableRevocations =
            SwitchableRevocations((tokenStore as CoCacheTokenStore).reader, revocationOutage)

        @Bean
        fun switchableFamilies(familyOutage: Outage): TokenFamilyStore =
            SwitchableFamilies(InMemoryTokenFamilyStore(), familyOutage)

        /** A bug of the application's own code: neither a refusal nor an unavailable store. */
        @Bean
        fun membershipRefresh(membershipBug: Outage): MembershipRefresh<RefreshTokenCredentials> =
            MembershipRefresh { _, _ ->
                check(!membershipBug.on) { "a bug in the membership lookup" }
                null
            }

        /** The application's CORS source, of its own stack's interface: the starter puts a CORS filter before CoSec. */
        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        fun servletCors(): org.springframework.web.cors.CorsConfigurationSource =
            org.springframework.web.cors.UrlBasedCorsConfigurationSource().apply {
                registerCorsConfiguration(
                    "/**",
                    cors()
                )
            }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        fun reactiveCors(): org.springframework.web.cors.reactive.CorsConfigurationSource =
            org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource().apply {
                registerCorsConfiguration("/**", cors())
            }

        private fun cors() = CorsConfiguration().apply {
            addAllowedOrigin(ORIGIN)
            addAllowedMethod("POST")
            addAllowedHeader("*")
        }
    }

    private val context: ConfigurableApplicationContext =
        SpringApplicationBuilder(App::class.java, *extra)
            .web(stack.type)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*(HIDDEN + stack.hidden).toTypedArray())))
            .properties(
                "server.port=0",
                "cocache.backend=memory",
                "cosec.jwt.secret=served-login-route-secret-served-login-route",
                "cosec.authorization.routes.location=",
                "cosec.authorization.snapshot.revalidate-interval=1s",
                "cosec.authentication.login.refresh.path=$REFRESH",
                "cosec.authentication.login.logout.path=$LOGOUT",
                "logging.level.me.ahoo.cosec=DEBUG",
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" + gatewayConfigurations().joinToString(","),
            )
            .run()

    val port: Int = (context as WebServerApplicationContext).webServer!!.port
    val revocations: SwitchableRevocations = context.getBean(SwitchableRevocations::class.java)
    val revocationOutage: Outage = context.getBean("revocationOutage", Outage::class.java)
    val familyOutage: Outage = context.getBean("familyOutage", Outage::class.java)
    val membershipBug: Outage = context.getBean("membershipBug", Outage::class.java)
    private val http = HttpClient.newHttpClient()

    init {
        runBlocking {
            val revisions = context.getBean(Revisions::class.java)
            revisions.publish(
                SnapshotKey.Platform,
                revisions.head(SnapshotKey.Platform),
                PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", document.encodeToByteArray()),
                Publication("served", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("served"))),
            )
        }
        // Ready once the pipeline decides with the document: a GET of /hello is permitted only by it.
        val deadline = System.nanoTime() + 30_000_000_000
        while (send("GET", "/hello").statusCode() == 401) {
            check(System.nanoTime() < deadline) { "the document was never installed" }
            Thread.sleep(100)
        }
    }

    /** A pair as a login issues it, in a session of its own. */
    fun login(user: String): CompositeToken {
        val families = context.getBean(TokenFamilies::class.java)
        val issue = runBlocking { families.start(TenantId.DEFAULT, PrincipalId(user)) }
        return context.getBean(TokenConverter::class.java).toToken(TokenSubject(user, null).inSession(issue))
    }

    fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The host's bean of [type], for a test that asks the wiring itself. */
    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    override fun close() = context.close()

    companion object {
        const val REFRESH = "/auth/refresh"
        const val LOGOUT = "/auth/logout"
        const val ORIGIN = "https://app.example.com"

        private val HIDDEN = listOf(
            GATEWAY_GLOBAL_FILTER,
            "me.ahoo.cosec.kafka",
            "me.ahoo.cosec.social",
            "me.ahoo.cosec.openapi",
            "me.ahoo.cosec.cosid",
            "me.ahoo.cosec.opentelemetry",
            "me.ahoo.cosec.micrometer",
            "me.ahoo.cosec.ip2region",
        )

        private fun gatewayConfigurations(): List<String> =
            ImportCandidates.load(AutoConfiguration::class.java, ServedLoginRouteHost::class.java.classLoader)
                .filter { it.startsWith("org.springframework.cloud.gateway.") }

        /** Login, refresh and logout for anyone; GET /hello for anyone, which tells when the document is installed. */
        val PERMITTING: String = document(
            """{"effect":"allow","action":"http:POST","resource":["http:path:$REFRESH","http:path:$LOGOUT"]},""",
        )

        /** No statement for the login routes: what an operator who forgot them deploys. */
        val FORGETTING: String = document("")

        private fun document(loginStatement: String): String =
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[""" +
                """{"id":"public","statements":[$loginStatement""" +
                """{"effect":"allow","action":"http:GET","resource":"http:path:/hello"}]}],""" +
                """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"}]}"""
    }
}
