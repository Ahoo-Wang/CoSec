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

package me.ahoo.cosec.spring.boot.pipeline

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.apikey.CoSecApiKeyAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.test.util.ReflectionTestUtils

/** `cosec.pipeline.mode`: a host that decides with policies, or a service behind a gateway that wires none of them. */
class PipelineModeTest {
    private val configurations = AutoConfigurations.of(
        CoSecJwtAutoConfiguration::class.java,
        CoSecApiKeyAutoConfiguration::class.java,
        CoSecPipelineIntegrationAutoConfiguration::class.java,
        CoSecSnapshotAutoConfiguration::class.java,
        CoSecPipelineAutoConfiguration::class.java,
        CoSecGatewayModeAutoConfiguration::class.java,
        CoSecPipelineWebAutoConfiguration::class.java,
    )

    @Test
    fun `behind a gateway only the identity gate is wired`() {
        WebApplicationContextRunner()
            .withConfiguration(configurations)
            .withPropertyValues(
                "cosec.pipeline.mode=gateway",
                "cosec.pipeline.identity.max-age=30s",
            )
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(Pipeline::class.java).assert().isEmpty()
                context.getBeansOfType(SnapshotDocumentStore::class.java).assert().isEmpty()
                val gate = context.getBean(RequestGate::class.java)
                gate.assert().isInstanceOf(IdentityGate::class.java)
                ReflectionTestUtils.getField(gate, "maxAge").assert().isEqualTo(java.time.Duration.ofSeconds(30))
                val registration = context.getBean(FilterRegistrationBean::class.java)
                val filter = registration.filter as PipelineServletFilter
                ReflectionTestUtils.getField(filter, "pipeline").assert().isSameAs(gate)
            }
    }

    /** U9: nothing there verifies a token, so nothing asks for a secret or a token store unless the service issues. */
    @Test
    fun `behind a gateway JWT is off unless turned on`() {
        val gateway = WebApplicationContextRunner().withConfiguration(configurations)
            .withPropertyValues("cosec.pipeline.mode=gateway")
        gateway.run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(TokenConverter::class.java).assert().isEmpty()
        }
        gateway.withPropertyValues(
            "${JwtProperties.PREFIX}.enabled=true",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
        ).withBean(TokenStore::class.java, { mockk<TokenStore>() })
            .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
            // A service that issues tokens reads their revocations, for refresh and logout.
            .withBean(RevocationReader::class.java, { RevocationReader.NONE }).run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(TokenConverter::class.java).assert().hasSize(1)
            }
    }

    @Test
    fun `an enforcing host has JWT on unless turned off`() {
        enforcing.run { context ->
            context.getBeansOfType(TokenConverter::class.java).assert().hasSize(1)
        }
        enforcing.withPropertyValues("${JwtProperties.PREFIX}.enabled=false").run { context ->
            context.getBeansOfType(TokenConverter::class.java).assert().isEmpty()
        }
    }

    private val enforcing = ApplicationContextRunner()
        .withConfiguration(configurations)
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    @Test
    fun `a gateway forwards the verified identity unless told not to`() {
        enforcing.withPropertyValues("cosec.gateway.identity.claims=name,email").run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(IdentityGate::class.java).assert().isEmpty()
            val issuer = context.getBean(IdentityIssuer::class.java)
            ReflectionTestUtils.getField(issuer, "claims").assert().isEqualTo(setOf("name", "email"))
            ReflectionTestUtils.getField(context.getBean(Pipeline::class.java), "identity").assert().isSameAs(issuer)
        }
        enforcing.withPropertyValues("cosec.gateway.identity.enabled=false").run { context ->
            context.getBeansOfType(IdentityIssuer::class.java).assert().isEmpty()
        }
        // Not a gateway: nothing to forward to.
        enforcing.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER)).run { context ->
            context.getBeansOfType(IdentityIssuer::class.java).assert().isEmpty()
        }
    }
}
