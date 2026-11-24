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

package me.ahoo.cosec.clienthost

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.webflux.ServerWebExchanges.requireSecurityContext
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** The sign-in a client posts: whoever names themselves is signed in, because the host tests the client, not a login. */
data class ClientHostLogin(val username: String) : LoginCredentials

internal class ClientHostLoginAuthentication : Authentication<ClientHostLogin> {
    override val credentialsType: Class<ClientHostLogin> = ClientHostLogin::class.java

    override suspend fun authenticate(credentials: ClientHostLogin): TokenSubject =
        TokenSubject(id = credentials.username, app = null)
}

/** What reached the host, so a test can say how many refreshes were on the wire. */
internal class ClientHostCounters {
    val logins = AtomicInteger()
    val refreshes = AtomicInteger()

    /** Set by `/test/refresh-error`: until turned off, every refresh fails with a bug of the application's own code. */
    @Volatile
    var refreshError = false
}

/** How long ago the access token of `/test/login-expired` expired: a token the host refuses and the client may believe. */
private val EXPIRED_AGO = Duration.ofMinutes(1)

/** Where the starter serves the refresh (`cosec.authentication.login.refresh.path`) and the logout. */
internal const val REFRESH_PATH = "/auth/refresh"
internal const val LOGOUT_PATH = "/auth/logout"

/**
 * The application: its sign-in and its tenant switch (both stay the application's), one route the document permits, one it permits to an
 * authenticated caller and one it denies, and the `/test` routes the client's tests ask of the host. Refresh and logout
 * are the starter's own routes (`cosec.authentication.login.refresh.path`, `logout.path`: `IDN-23`, `IDN-24`), the
 * ones every application gets.
 *
 * Never an example of a production host: its login signs in anyone who names themselves, its secret is a constant, and
 * the `/test` routes (counters, echo, a session ended on request, an expired sign-in, the outage switches of the
 * revocations and the session store) are open to every caller. They
 * pass CoSec like every other route: the document permits them, and nothing is exempted from the filter (STA-4).
 */
@RestController
internal class ClientHostApi(
    private val auth: TokenCompositeAuthentication,
    private val verifier: TokenVerifier,
    private val families: TokenFamilies,
    private val converter: TokenConverter,
    private val counters: ClientHostCounters,
    private val revocations: SwitchableRevocations,
    private val sessions: SwitchableSessions,
) {
    @GetMapping("/hello")
    fun hello(): Map<String, String> = mapOf("message" to "hello")

    // JSON, not text: a path variable echoed as text/html is a cross-site scripting sink (CodeQL).
    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String): Map<String, String> = mapOf("order" to id)

    @DeleteMapping("/orders/{id}")
    fun delete(@PathVariable id: String): Map<String, String> = mapOf("deleted" to id)

    @PostMapping("/auth/login")
    suspend fun login(@RequestBody body: Map<String, String>): CompositeToken {
        counters.logins.incrementAndGet()
        return auth.authenticateAsToken(ClientHostLogin(body.getValue("username")))
    }

    /** The application's tenant switch ([answerSwitch]): open to authenticated callers, the pair in the body. */
    @PostMapping("$SWITCH_PATH/{tenant}")
    suspend fun switch(
        @PathVariable tenant: String,
        @RequestBody body: Map<String, Any?>,
        exchange: ServerWebExchange,
    ): ResponseEntity<Any> = answerSwitch(auth, tenant, body, exchange.requireSecurityContext())

    /** The headers this request arrived with, lower-cased: what a client put on the wire. */
    @GetMapping("/test/echo")
    fun echo(@RequestHeader headers: HttpHeaders): Map<String, String> =
        headers.toSingleValueMap().mapKeys { it.key.lowercase() }

    /**
     * A sign-in whose access token has already expired, and whose session and refresh token are live: what a client holds
     * when its token ran out. The host refuses it `401 token-expired` whatever its clock, so a test waits for nothing: a
     * client that reads the expiry refreshes before it sends, one whose clock is behind sends it and is refused.
     */
    @PostMapping("/test/login-expired")
    suspend fun loginExpired(@RequestBody body: Map<String, String>): CompositeToken {
        counters.logins.incrementAndGet()
        val subject = TokenSubject(id = body.getValue("username"), app = null)
        val issued = subject.inSession(families.start(TenantId.DEFAULT, PrincipalId(subject.id)))
        return converter.toToken(issued, EXPIRED_AGO.negated(), Duration.ofDays(1))
    }

    /**
     * The revocations cannot be read from now on, until `/test/revocations/up`: the refresh's own read fails, and the
     * served route answers 503 (`IDN-14`). The pipeline's verification of a request's token reads them too, and lets the
     * token through (`PIP-7`), so this stays on rather than failing one read, which that verification could take.
     */
    @PostMapping("/test/revocations/down")
    fun revocationsDown() {
        revocations.down = true
    }

    @PostMapping("/test/revocations/up")
    fun revocationsUp() {
        revocations.down = false
    }

    /** The session store cannot answer from now on, until `/test/sessions/up`: a refresh or a logout is `503`. */
    @PostMapping("/test/sessions/down")
    fun sessionsDown() {
        sessions.down = true
    }

    @PostMapping("/test/sessions/up")
    fun sessionsUp() {
        sessions.down = false
    }

    /** The ids of the tokens whose revocation read failed while the revocations were down. */
    @GetMapping("/test/revocations/failed")
    fun failedReads(): List<String> = revocations.failedReads.toList()

    /** Every refresh fails with a bug of the application's own code until turned off: the route answers 500. */
    @PostMapping("/test/refresh-error/{on}")
    fun refreshError(@PathVariable on: Boolean) {
        counters.refreshError = on
    }

    @GetMapping("/test/stats")
    fun stats(): Map<String, Int> = mapOf("logins" to counters.logins.get(), "refreshes" to counters.refreshes.get())

    @PostMapping("/test/reset")
    fun reset() {
        counters.logins.set(0)
        counters.refreshes.set(0)
        counters.refreshError = false
        revocations.down = false
        revocations.failedReads.clear()
        sessions.down = false
    }

    /** Ends the session of [body]'s pair, as a server-side sign-out or a revocation would: its next refresh is a 401. */
    @PostMapping("/test/end-session")
    suspend fun endSession(@RequestBody body: Map<String, String>) {
        val token = verifier.refresh(CompositeToken(body.getValue("accessToken"), body.getValue("refreshToken")))
        families.end(checkNotNull(token.session) { "The token belongs to no session." }.id)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
internal class ClientHostApplication {
    @Bean
    fun clientHostCounters() = ClientHostCounters()

    @Bean
    fun clientHostLogin() = ClientHostLoginAuthentication()

    /** The application's membership refresh: a bug while `/test/refresh-error` is on, else the token's claims. */
    @Bean
    fun clientHostMembershipRefresh(counters: ClientHostCounters): MembershipRefresh<RefreshTokenCredentials> =
        MembershipRefresh { _, _ ->
            check(!counters.refreshError) { "a bug in the application's membership lookup" }
            null
        }

    /** The application's tenant switch, which the login finds by its credentials class. */
    @Bean
    fun clientHostSwitch(verifier: TokenVerifier, families: TokenFamilies, revocation: TokenRevocation) =
        clientHostSwitchAuthentication(verifier, families, revocation)

    /** The session store, which `/test/sessions/down` takes down: CoCache's in-memory one is not switchable. */
    @Bean
    fun clientHostSessions(): SwitchableSessions = SwitchableSessions()

    @Bean
    fun clientHostRevocations(tokenStore: TokenStore): SwitchableRevocations =
        SwitchableRevocations((tokenStore as CoCacheTokenStore).reader)

    /** Counts the refreshes on the wire, before CoSec decides them: what a test asserts about one refresh for many calls. */
    @Bean
    fun clientHostRefreshCounter(counters: ClientHostCounters): WebFilter = WebFilter { exchange, chain ->
        val request = exchange.request
        if (request.method == HttpMethod.POST && request.path.value() == REFRESH_PATH) counters.refreshes.incrementAndGet()
        chain.filter(exchange)
    }

    @Bean
    fun clientHostApi(
        auth: TokenCompositeAuthentication,
        verifier: TokenVerifier,
        families: TokenFamilies,
        converter: TokenConverter,
        counters: ClientHostCounters,
        revocations: SwitchableRevocations,
        sessions: SwitchableSessions,
    ) = ClientHostApi(auth, verifier, families, converter, counters, revocations, sessions)
}
