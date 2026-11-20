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

package me.ahoo.cosec.spring.boot.realserver

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `IDN-25`: a request, a refresh and a logout verify with one `TokenVerifier`, the application's when it declares one;
 * the served refresh route refreshes with the application's `RefreshTokenAuthentication` when it declares one, its hook
 * included. On Reactor Netty; both stacks share this wiring (`ServedLoginRoutesTest` runs the routes on both).
 */
class OneVerifierTest {
    /** An application verifier stricter than CoSec's: it refuses every token, as an audience check refuses a stranger. */
    @Configuration(proxyBeanMethods = false)
    class Refusing {
        @Bean
        fun tokenVerifier(): TokenVerifier = object : TokenVerifier {
            override fun verify(accessToken: String): VerifiedToken = throw TokenVerificationException("not ours")

            override fun refresh(token: CompositeToken): VerifiedToken = throw TokenVerificationException("not ours")
        }
    }

    /** An application refresh whose hook refuses a disabled user, beside the host's own `MembershipRefresh` bean. */
    @Configuration(proxyBeanMethods = false)
    class RefusingDisabled {
        @Bean
        fun refreshTokenAuthentication(
            verifier: TokenVerifier,
            revocation: TokenRevocation,
            families: TokenFamilies,
        ): RefreshTokenAuthentication<RefreshTokenCredentials> =
            RefreshTokenAuthentication(verifier, revocation, families) { token, _ ->
                if (token.principal == "disabled") throw TokenRevokedException("User [disabled] is disabled.")
                null
            }
    }

    /**
     * An application verifier that accepts what CoSec's own accepts and adds a check of its own: it is not CoSec's
     * verifier (`CoSecTokenVerifier` is internal), so its tokens are not CoSec's on a request.
     */
    @Configuration(proxyBeanMethods = false)
    class Wrapping {
        @Bean
        fun tokenVerifier(signing: JwtSigning): TokenVerifier {
            val own = JwtTokenVerifier(signing)
            return object : TokenVerifier {
                override fun verify(accessToken: String): VerifiedToken = own.verify(accessToken).also {
                    if (it.principal == "stranger") throw TokenVerificationException("not for this service")
                }

                override fun refresh(token: CompositeToken): VerifiedToken = own.refresh(token)
            }
        }
    }

    /** A refresh over a credentials class of the application's own, which the served route cannot present. */
    @Configuration(proxyBeanMethods = false)
    class OwnCredentials {
        class Renew(override val accessToken: String, override val refreshToken: String) : RefreshTokenCredentials

        @Bean
        fun refreshTokenAuthentication(
            verifier: TokenVerifier,
            revocation: TokenRevocation,
            families: TokenFamilies,
        ): RefreshTokenAuthentication<Renew> =
            RefreshTokenAuthentication(verifier, revocation, families, Renew::class.java) { _, _ -> null }
    }

    private fun body(pair: CompositeToken) =
        """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}"}"""

    @Test
    fun `an application verifier that refuses a token refuses it on a request, a refresh and a logout alike`() {
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING, Refusing::class.java).use { host ->
            val pair = host.login("alice")
            // CoSec's own verifier would accept it: a path the document does not permit would then be 403.
            val request = host.send(
                "GET",
                "/orders",
                headers = mapOf("Authorization" to "Bearer ${pair.accessToken}"),
            )
            request.statusCode().assert().isEqualTo(401)
            request.body().assert().contains("token-invalid")
            val refresh = host.send("POST", ServedLoginRouteHost.REFRESH, body(pair))
            refresh.statusCode().assert().isEqualTo(401)
            refresh.body().assert().contains("token-invalid")
            val logout = host.send("POST", ServedLoginRouteHost.LOGOUT, body(pair))
            logout.statusCode().assert().isEqualTo(401)
            logout.body().assert().contains("token-invalid")
        }
    }

    @Test
    fun `with CoSec's own verifier the same token is the principal on the request`() {
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING).use { host ->
            val pair = host.login("alice")
            host.send("GET", "/orders", headers = mapOf("Authorization" to "Bearer ${pair.accessToken}"))
                .statusCode().assert().isEqualTo(403)
        }
    }

    @Test
    fun `CoSec's own verifier answers a request's token from the cache, and a logout still refuses it next`() {
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING).use { host ->
            val cache = host.bean(VerifiedTokenCache::class.java)
            val pair = host.login("alice")
            val bearer = mapOf("Authorization" to "Bearer ${pair.accessToken}")
            repeat(2) { host.send("GET", "/orders", headers = bearer).statusCode().assert().isEqualTo(403) }
            cache.misses.assert().isEqualTo(1L)
            cache.hits.assert().isEqualTo(1L)
            host.send("POST", ServedLoginRouteHost.LOGOUT, body(pair)).statusCode().assert().isEqualTo(204)
            // Answered from the cache, then refused by the revocation check that runs after it (IDN-32).
            val revoked = host.send("GET", "/orders", headers = bearer)
            revoked.statusCode().assert().isEqualTo(401)
            revoked.body().assert().contains("token-revoked")
            cache.hits.assert().isEqualTo(2L)
        }
    }

    @Test
    fun `an application's own verifier is never given the cache`() {
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING, Wrapping::class.java).use { host ->
            val cache = host.bean(VerifiedTokenCache::class.java)
            val bearer = mapOf("Authorization" to "Bearer ${host.login("alice").accessToken}")
            repeat(2) { host.send("GET", "/orders", headers = bearer).statusCode().assert().isEqualTo(403) }
            (cache.hits + cache.misses).assert().isEqualTo(0L)
        }
    }

    @Test
    fun `the served refresh route refreshes with the application's own refresh authentication, its hook included`() {
        ServedLoginRouteHost(
            ServedStack.NETTY,
            ServedLoginRouteHost.PERMITTING,
            RefusingDisabled::class.java
        ).use { host ->
            val disabled = host.send("POST", ServedLoginRouteHost.REFRESH, body(host.login("disabled")))
            disabled.statusCode().assert().isEqualTo(401)
            disabled.body().assert().contains("token-revoked")
            host.send("POST", ServedLoginRouteHost.REFRESH, body(host.login("alice")))
                .statusCode().assert().isEqualTo(200)
        }
    }

    @Test
    fun `a refresh authentication over credentials of the application's own fails startup with the route set`() {
        val failure = assertThrows<Exception> {
            ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING, OwnCredentials::class.java).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }
            .any { "IDN-25" in it && OwnCredentials.Renew::class.java.name in it }.assert().isTrue()
    }

    @Test
    fun `an application verifier that wraps CoSec's own makes a request's token not CoSec's, without session or break-glass`() {
        fun principal(host: ServedLoginRouteHost): AuthenticatedPrincipal {
            val token = host.login("alice").accessToken
            val result = runBlocking { host.bean(Authenticator::class.java).authenticate(Credentials(token, null)) }
            return (result as AuthnResult.Authenticated).principal
        }
        // CoSec's own verifier: CoSec's token, its session and break-glass.
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING).use { host ->
            val own = principal(host)
            own.credential?.method.assert().isEqualTo(Identity.Method.JWT)
            own.session.assert().isNotNull()
            own.breakGlass.assert().isTrue()
        }
        // The application's verifier accepts the same token, which is then another issuer's on every request.
        ServedLoginRouteHost(ServedStack.NETTY, ServedLoginRouteHost.PERMITTING, Wrapping::class.java).use { host ->
            val wrapped = principal(host)
            wrapped.credential?.method.assert().isEqualTo(Identity.Method("token"))
            wrapped.session.assert().isNull()
            wrapped.breakGlass.assert().isFalse()
        }
    }
}
