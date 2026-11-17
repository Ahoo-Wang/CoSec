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
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The sign-in a client posts: whoever names themselves is signed in, because the host tests the client, not a login. */
data class ClientHostLogin(val username: String) : LoginCredentials

/** The refresh a client posts: the pair it holds, as `TokenRefresher` sends it. */
data class ClientHostRefresh(override val accessToken: String, override val refreshToken: String) :
    RefreshTokenCredentials

internal class ClientHostLoginAuthentication : Authentication<ClientHostLogin> {
    override val credentialsType: Class<ClientHostLogin> = ClientHostLogin::class.java

    override suspend fun authenticate(credentials: ClientHostLogin): TokenSubject =
        TokenSubject(id = credentials.username, app = null)
}

/** What reached the host, so a test can say how many refreshes were on the wire. */
internal class ClientHostCounters {
    val logins = AtomicInteger()
    val refreshes = AtomicInteger()

    /** Set by `/test/fail-next-refresh`: the next refresh fails as a store that cannot be read does. */
    val failNextRefresh = AtomicBoolean()
}

/** How long ago the access token of `/test/login-expired` expired: a token the host refuses and the client may believe. */
private val EXPIRED_AGO = Duration.ofMinutes(1)

/**
 * The application: the sign-in and the refresh route CoSec leaves to it, one route the document permits, one it
 * permits to an authenticated caller and one it denies, and the `/test` routes the client's tests ask of the host.
 *
 * Never an example of a production host: its login signs in anyone who names themselves, its secret is a constant, and
 * the `/test` routes (counters, echo, a session ended on request, an expired sign-in) are open to every caller. They
 * pass CoSec like every other route: the document permits them, and nothing is exempted from the filter (STA-4).
 */
@RestController
internal class ClientHostApi(
    private val auth: TokenCompositeAuthentication,
    private val verifier: TokenVerifier,
    private val families: TokenFamilies,
    private val converter: TokenConverter,
    private val counters: ClientHostCounters,
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

    @PostMapping("/auth/refresh")
    suspend fun refresh(@RequestBody body: Map<String, String>): CompositeToken {
        counters.refreshes.incrementAndGet()
        if (counters.failNextRefresh.compareAndSet(true, false)) {
            // What RefreshTokenAuthentication throws when the revocations cannot be read (IDN-14): nothing is issued.
            throw RevocationUnavailableException("The client host was asked to fail this refresh.")
        }
        return auth.authenticateAsToken(ClientHostRefresh(body.getValue("accessToken"), body.getValue("refreshToken")))
    }

    /** What a failed refresh means is CoSec's, the status is the application's (wiki/integration/clients.md). */
    @ExceptionHandler(TokenVerificationException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun refused() = Unit

    @ExceptionHandler(RevocationUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun unavailable() = Unit

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

    /** The next refresh answers 503 through the exception a store that cannot be read throws, and the one after succeeds. */
    @PostMapping("/test/fail-next-refresh")
    fun failNextRefresh() {
        counters.failNextRefresh.set(true)
    }

    @GetMapping("/test/stats")
    fun stats(): Map<String, Int> = mapOf("logins" to counters.logins.get(), "refreshes" to counters.refreshes.get())

    @PostMapping("/test/reset")
    fun reset() {
        counters.logins.set(0)
        counters.refreshes.set(0)
        counters.failNextRefresh.set(false)
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

    @Bean
    fun clientHostRefresh(
        tokenVerifier: TokenVerifier,
        tokenRevocation: TokenRevocation,
        tokenFamilies: TokenFamilies,
    ) = RefreshTokenAuthentication(tokenVerifier, tokenRevocation, tokenFamilies)

    @Bean
    fun clientHostApi(
        auth: TokenCompositeAuthentication,
        verifier: TokenVerifier,
        families: TokenFamilies,
        converter: TokenConverter,
        counters: ClientHostCounters,
    ) = ClientHostApi(auth, verifier, families, converter, counters)
}
