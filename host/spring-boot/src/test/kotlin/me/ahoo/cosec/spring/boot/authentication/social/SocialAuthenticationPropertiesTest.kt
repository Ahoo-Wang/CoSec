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

package me.ahoo.cosec.spring.boot.authentication.social

import me.ahoo.test.asserts.assert
import me.zhyd.oauth.config.AuthDefaultSource
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.util.UUID

class SocialAuthenticationPropertiesTest {

    @Test
    fun toJustAuthConfig() {
        val provider = SocialAuthenticationProperties.Provider(
            type = AuthDefaultSource.GITHUB,
            clientId = UUID.randomUUID().toString(),
            clientSecret = UUID.randomUUID().toString(),
            redirectUri = UUID.randomUUID().toString(),
        )
        val authConfig = provider.toJustAuthConfig()
        authConfig.clientId.assert().isEqualTo(provider.clientId)
        authConfig.clientSecret.assert().isEqualTo(provider.clientSecret)
        authConfig.redirectUri.assert().isEqualTo(provider.redirectUri)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SocialAuthenticationProperties::class)
    class Bound

    private val runner = ApplicationContextRunner().withUserConfiguration(Bound::class.java).withPropertyValues(
        "cosec.authentication.login.social.registration.github.type=github",
        "cosec.authentication.login.social.registration.github.client-id=id",
    )

    /** Round 2 L4: a misspelt field inside a registration fails startup instead of leaving the provider as it was. */
    @Test
    fun `an unknown field of a registration fails startup`() {
        runner.withPropertyValues("cosec.authentication.login.social.registration.github.pcke=true").run { context ->
            val messages = generateSequence(
                context.startupFailure
            ) { it.cause }.mapNotNull { it.message }.joinToString()
            messages.assert().contains("cosec.authentication.login.social.registration.github.pcke")
        }
    }

    @Test
    fun `the known fields of a registration bind`() {
        runner.withPropertyValues("cosec.authentication.login.social.registration.github.pkce=true").run { context ->
            context.startupFailure.assert().isNull()
            val github = context.getBean(SocialAuthenticationProperties::class.java).registration.getValue("github")
            github.pkce.assert().isTrue()
            github.clientId.assert().isEqualTo("id")
        }
    }
}
