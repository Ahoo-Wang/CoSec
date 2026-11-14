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

package me.ahoo.cosec.login

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeAuthenticationTest {
    private val authenticationProvider = DefaultAuthenticationProvider()
    private val compositeAuthentication = CompositeAuthentication(authenticationProvider)

    @Test
    fun getSupportCredentials() {
        compositeAuthentication.supportCredentials.assert().isEqualTo(Credentials::class.java)
    }

    @Test
    fun authenticate() = runTest {
        val credentials = mockk<Credentials>()
        val authentication = mockk<Authentication<Credentials>> {
            every { supportCredentials } returns credentials.javaClass
            coEvery { authenticate(any()) } returns TokenSubject("alice", null)
        }
        authenticationProvider.register(credentials.javaClass, authentication)

        compositeAuthentication.authenticate(credentials).assert().isEqualTo(TokenSubject("alice", null))
    }

    @Test
    fun `credentials no authentication supports are refused`() = runTest {
        assertThrows<IllegalArgumentException> { compositeAuthentication.authenticate(mockk<Credentials>()) }
    }
}
