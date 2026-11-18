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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
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
}
