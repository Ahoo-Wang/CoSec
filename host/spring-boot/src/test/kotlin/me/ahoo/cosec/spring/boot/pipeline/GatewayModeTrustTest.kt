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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.UnknownProperties
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.filter.HiddenHttpMethodFilter

/**
 * `STA-6` and `STA-7`: what a service in `cosec.pipeline.mode=gateway` trusts. It believes any `CoSec-Identity` it
 * receives, so the operator states that only the gateway can reach it, or startup fails; and the gateway decided the
 * method of the request line, so a hidden-method filter in the service, which would run another, fails startup too.
 */
class GatewayModeTrustTest {
    private val statement = "cosec.pipeline.identity.only-gateway-can-reach"

    private val gateway = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecGatewayModeAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("cosec.pipeline.mode=gateway", "cosec.jwt.enabled=false")

    private fun failure(runner: WebApplicationContextRunner): String {
        var message = ""
        runner.run { context ->
            context.startupFailure.assert().isNotNull()
            message = generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        }
        return message
    }

    /** The variable the refusal names must be one the starter itself accepts: Spring maps each `_` to a `.`, so the words of a key are joined. */
    @Test
    fun `the environment variable the refusal names is read by the statement and accepted by the property check`() {
        fun environment(variable: String) = StandardEnvironment().apply {
            propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf(variable to "true")
                )
            )
        }
        val check = UnknownProperties.load(javaClass.classLoader)
        for ((variable, key) in mapOf(
            "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH" to statement,
            "COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH" to "cosec.pipeline.identity.only-trusted-callers-can-reach",
        )) {
            val environment = environment(variable)
            GatewayModeTrust.requireBoundaryStated(environment).assert().isEqualTo(key)
            check.of(environment).assert().isEmpty()
        }
        // With every word apart it is another key, which the property check refuses.
        check.of(environment("COSEC_PIPELINE_IDENTITY_ONLY_GATEWAY_CAN_REACH")).assert()
            .containsExactly("cosec.pipeline.identity.only.gateway.can.reach")
    }

    @Test
    fun `a service in gateway mode does not start until the operator states the boundary, and the message says what to write`() {
        val message = failure(gateway)
        message.assert().contains("believes any CoSec-Identity header")
            .contains("no signature")
            .contains("NetworkPolicy")
            .contains("mutual TLS")
            .contains("$statement=true")
            .contains("COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true")
            .contains("It is not set")
            .contains("STA-7")
    }

    /** `cosec-testkit`'s statement for a test (#1841) is the starter's own key, set to `true`: a test that makes it starts. */
    @Test
    fun `the kit's statement for a test is the key the starter reads, and a service in gateway mode starts with it`() {
        IdentityHeader.ONLY_GATEWAY_CAN_REACH.assert().isEqualTo(
            "${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY}=true"
        )
        // The key apart (#1872), for a launcher that takes a property map.
        IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY.assert().isEqualTo(PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY)
        gateway.withPropertyValues(IdentityHeader.ONLY_GATEWAY_CAN_REACH).run { it.startupFailure.assert().isNull() }
    }

    /**
     * The message describes the host it is about (#1842): a service in `gateway` mode wires no documents and no control
     * plane, so a caller who claims any principal reaches its routes and nothing more; publishing and approving are named
     * only where the control plane runs.
     */
    @Test
    fun `a service in gateway mode is told what a claimed principal reaches there, and never of publishing or approving`() {
        val message = failure(gateway)
        message.assert().contains("cosec.pipeline.mode=gateway: this host believes any CoSec-Identity header")
            .contains("whoever they claim to be to this service's routes, any user of any tenant")
            .doesNotContain("publish")
            .doesNotContain("approve")
            .doesNotContain("audit")
            .doesNotContain("contains mistakes")
            .doesNotContain("compromised workload")
        // Even where the control plane's classes are on the classpath, as in this test, gateway mode has none.
        GatewayModeTrust.controlPlaneWired(environment("cosec.pipeline.mode" to "gateway")).assert().isFalse()
    }

    @Test
    fun `a host that runs the control plane is told a claimed principal can publish and approve its own changes`() {
        val environment = environment("cosec.pipeline.caller" to "forwarded-identity")
        GatewayModeTrust.controlPlaneWired(environment).assert().isTrue()
        val message = runCatching { GatewayModeTrust.requireBoundaryStated(environment) }.exceptionOrNull()!!.message!!
        message.assert().startsWith(
            "cosec.pipeline.caller=forwarded-identity: this host believes any CoSec-Identity header"
        )
            .contains("the platform administrator included, and can publish any policy of any tenant")
            .contains(
                "It still refuses an invalid change and records every change in the audit under the principal claimed"
            )
            .contains("a compromised workload that can reach this host, which can also approve its own changes")
            .doesNotContain("contains mistakes")
            .contains("$statement=true")
        // Switched off, the control plane is not there to publish through.
        GatewayModeTrust.controlPlaneWired(
            environment("cosec.pipeline.caller" to "forwarded-identity", "cosec.control.enabled" to "false")
        ).assert().isFalse()
    }

    @Test
    fun `a host without the control plane, such as one with its own gate, is never told of publishing or approving`() {
        val environment = environment("cosec.pipeline.mode" to "enforce", "cosec.control.enabled" to "false")
        val host = "the web gate of this host is an IdentityGate bean of the application, [ownGate]"
        val message = runCatching {
            GatewayModeTrust.requireBoundaryStated(environment, host)
        }.exceptionOrNull()!!.message!!
        message.assert().startsWith("$host: this host believes any CoSec-Identity header")
            .contains("whoever they claim to be to this host's routes, the platform administrator included")
            .doesNotContain("publish")
            .doesNotContain("approve")
            .doesNotContain("contains mistakes")
    }

    private fun environment(vararg properties: Pair<String, String>) = StandardEnvironment().apply {
        propertySources.addFirst(org.springframework.core.env.MapPropertySource("test", mapOf(*properties)))
    }

    @Test
    fun `false and every value but true fail like an absent one, and say what was written`() {
        for (value in listOf("false", "1", "on", "yes", "", "maybe")) {
            failure(gateway.withPropertyValues("$statement=$value")).assert()
                .describedAs("with $statement=[$value]").contains("It is [$value]").contains("$statement=true")
        }
    }

    @Test
    fun `with the statement the service starts, in any letter case of true, and an enforcing host needs none`() {
        for (value in listOf("true", "TRUE")) {
            gateway.withPropertyValues("$statement=$value").run { it.startupFailure.assert().isNull() }
        }
        // `enforce` wires none of this: no statement, and a hidden-method filter is the in-process host's own business.
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecGatewayModeAutoConfiguration::class.java))
            .withPropertyValues("cosec.pipeline.mode=enforce", "spring.mvc.hiddenmethod.filter.enabled=true")
            .run { it.startupFailure.assert().isNull() }
    }

    @Test
    fun `a hidden-method filter in a service behind a gateway fails startup, by property and by bean`() {
        val stated = gateway.withPropertyValues("$statement=true")
        for (property in listOf(
            "spring.mvc.hiddenmethod.filter.enabled",
            "spring.webflux.hiddenmethod.filter.enabled"
        )) {
            failure(stated.withPropertyValues("$property=true")).assert()
                .contains("$property=true")
                .contains("_method")
                .contains("http:DELETE")
                .contains("STA-6")
        }
        failure(
            stated.withBean("methodFilter", HiddenHttpMethodFilter::class.java, { HiddenHttpMethodFilter() })
        ).assert()
            .contains("a HiddenHttpMethodFilter bean, [methodFilter]")
        // Registered without a bean of its own: `FilterRegistrationBean(HiddenHttpMethodFilter())`, as a servlet app may.
        failure(
            stated.withBean("methodFilterRegistration", FilterRegistrationBean::class.java, {
                FilterRegistrationBean(HiddenHttpMethodFilter())
            })
        ).assert().contains("a FilterRegistrationBean of a HiddenHttpMethodFilter, [methodFilterRegistration]")
        // Another filter's registration is not one.
        stated.withBean("otherRegistration", FilterRegistrationBean::class.java, {
            FilterRegistrationBean(PassThrough())
        }).run { it.startupFailure.assert().isNull() }
        // The default, and an explicit false, are no filter.
        stated.run { it.startupFailure.assert().isNull() }
        stated.withPropertyValues(
            "spring.mvc.hiddenmethod.filter.enabled=false"
        ).run { it.startupFailure.assert().isNull() }
    }

    @Test
    fun `a reactive service is held to the same two statements`() {
        val reactive = ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecGatewayModeAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("cosec.pipeline.mode=gateway", "cosec.jwt.enabled=false")
        reactive.run { it.startupFailure.assert().isNotNull() }
        reactive.withPropertyValues(
            "$statement=true",
            "spring.webflux.hiddenmethod.filter.enabled=true"
        ).run { context ->
            generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                .assert().contains("spring.webflux.hiddenmethod.filter.enabled=true")
        }
        reactive.withPropertyValues("$statement=true").run { it.startupFailure.assert().isNull() }
    }

    /**
     * The link a failed startup ends with opens a heading that exists, in the page and in its Chinese twin: a failure that
     * points at a dead anchor is worse than none. (No other test holds the links in the starter's messages to the wiki;
     * the docs check reads the wiki's own links only.)
     */
    @Test
    fun `the link in the boundary message is a heading of the deployment page, in both languages`() {
        val message = failure(gateway)
        val anchor = Regex(
            "https://cosec\\.ahoo\\.me/operations/deployment#([a-z0-9-]+)"
        ).find(message)!!.groupValues[1]
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.exists(it.resolve("settings.gradle.kts")) }
        val english = java.nio.file.Files.readAllLines(root.resolve("wiki/operations/deployment.md"))
        val slugs = english.filter { it.startsWith("#") }.map { heading ->
            heading.trimStart(
                '#',
                ' '
            ).substringBefore(" {#").lowercase().replace(Regex("[^a-z0-9 -]"), "").replace(' ', '-')
        }
        slugs.assert().describedAs("the headings of wiki/operations/deployment.md").contains(anchor)
        val chinese = java.nio.file.Files.readString(root.resolve("wiki/zh/operations/deployment.md"))
        chinese.assert().contains("{#$anchor}")
    }
}

private class PassThrough : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) =
        chain.doFilter(request, response)
}
