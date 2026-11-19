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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
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

    @Test
    fun `a service in gateway mode does not start until the operator states the boundary, and the message says what to write`() {
        val message = failure(gateway)
        message.assert().contains("believes any CoSec-Identity header")
            .contains("no signature")
            .contains("NetworkPolicy")
            .contains("mutual TLS")
            .contains("$statement=true")
            .contains("COSEC_PIPELINE_IDENTITY_ONLY_GATEWAY_CAN_REACH=true")
            .contains("It is not set")
            .contains("STA-7")
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
}
