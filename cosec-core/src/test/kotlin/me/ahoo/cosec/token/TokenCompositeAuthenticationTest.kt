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

package me.ahoo.cosec.token

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.authentication.CompositeAuthentication
import me.ahoo.cosec.authentication.DefaultAuthenticationProvider
import me.ahoo.cosec.principal.SimplePrincipal
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class TokenCompositeAuthenticationTest {

    @Test
    fun authenticateAsToken() {
        val compositeAuthentication = CompositeAuthentication(DefaultAuthenticationProvider)
        val compositeToken = SimpleCompositeToken("accessToken", "refreshToken")
        val tokenConverter = mockk<TokenConverter>() {
            every { asToken(any()) } returns compositeToken
        }
        val tokenCompositeAuthentication = TokenCompositeAuthentication(compositeAuthentication, tokenConverter)
        assertThat(tokenCompositeAuthentication.supportCredentials, `is`(Credentials::class.java))

        val credentials = mockk<Credentials>()
        val authentication = mockk<Authentication<Credentials, CoSecPrincipal>> {
            every { authenticate(any()) } returns SimplePrincipal.ANONYMOUS.toMono()
        }
        DefaultAuthenticationProvider.register(credentials.javaClass, authentication)

        tokenCompositeAuthentication.authenticateAsToken(credentials)
            .test()
            .consumeNextWith {
                assertThat(it, `is`(compositeToken))
            }
            .verifyComplete()
    }
}
