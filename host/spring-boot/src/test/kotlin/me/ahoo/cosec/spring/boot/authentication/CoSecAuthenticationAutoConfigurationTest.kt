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

package me.ahoo.cosec.spring.boot.authentication

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.AuthenticationProvider
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.Credentials
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecAuthenticationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withUserConfiguration(CoSecAuthenticationAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(LoginProperties::class.java)
                    .hasSingleBean(CoSecAuthenticationAutoConfiguration::class.java)
                    .hasSingleBean(AuthenticationProvider::class.java)
                    .hasSingleBean(CompositeAuthentication::class.java)
            }
    }

    @Test
    fun contextLoadsWhenDisable() {
        contextRunner
            .withPropertyValues("${ConditionalOnLoginEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(CoSecAuthenticationAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(LoginProperties::class.java)
                    .doesNotHaveBean(CoSecAuthenticationAutoConfiguration::class.java)
                    .doesNotHaveBean(AuthenticationProvider::class.java)
                    .doesNotHaveBean(CompositeAuthentication::class.java)
            }
    }

    /** #1222: a context answers with its own beans, whatever another context in the JVM registers or closes. */
    @Test
    fun `a context authenticates with its own beans only`() {
        contextAuthenticatingAs("first").run { first ->
            first.authenticated().assert().isEqualTo("first")
            contextAuthenticatingAs("second").run { second ->
                second.authenticated().assert().isEqualTo("second")
                first.authenticated().assert().isEqualTo("first")
            }
            first.authenticated().assert().isEqualTo("first")
        }
        contextRunner
            .withUserConfiguration(CoSecAuthenticationAutoConfiguration::class.java)
            .run { empty ->
                assertThrows<IllegalArgumentException> { empty.authenticated() }
            }
    }

    private fun contextAuthenticatingAs(principal: String) = contextRunner
        .withUserConfiguration(CoSecAuthenticationAutoConfiguration::class.java)
        .withBean(ContextAuthentication::class.java, { ContextAuthentication(principal) })

    private fun AssertableApplicationContext.authenticated(): String =
        runBlocking { getBean(CompositeAuthentication::class.java).authenticate(ContextCredentials).id }

    private object ContextCredentials : Credentials

    private class ContextAuthentication(private val principal: String) : Authentication<ContextCredentials> {
        override val supportCredentials = ContextCredentials::class.java

        override suspend fun authenticate(credentials: ContextCredentials): TokenSubject =
            TokenSubject(principal, null)
    }
}
