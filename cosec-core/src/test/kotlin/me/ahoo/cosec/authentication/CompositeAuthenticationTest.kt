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

package me.ahoo.cosec.authentication

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class CompositeAuthenticationTest {
    private val compositeAuthentication = CompositeAuthentication(DefaultAuthenticationProvider)

    @Test
    fun getSupportCredentials() {
        assertThat(compositeAuthentication.supportCredentials, `is`(Credentials::class.java))
    }

    @Test
    fun authenticate() {
        val credentials = mockk<Credentials>()
        val authentication = mockk<Authentication<Credentials, CoSecPrincipal>> {
            every { authenticate(any()) } returns SimplePrincipal.ANONYMOUS.toMono()
        }
        DefaultAuthenticationProvider.register(credentials.javaClass, authentication)

        compositeAuthentication.authenticate(credentials)
            .test()
            .consumeNextWith {
                assertThat(it, `is`(SimplePrincipal.ANONYMOUS))
            }
            .verifyComplete()
    }
}
