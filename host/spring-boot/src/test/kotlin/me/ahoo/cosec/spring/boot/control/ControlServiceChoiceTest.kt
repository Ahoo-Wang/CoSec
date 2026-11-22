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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.http.HttpControlService
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.spring.boot.CoSecModuleCheckAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration

/**
 * `STA-11`: an application has one `ControlService`. The in-process one where the instance runs the control plane, the
 * HTTP one where `cosec.control.service-url` names a control-plane host, none otherwise; an instance that runs the control
 * plane and names a host too does not start, and a host named without `cosec-control-http` fails naming the artifact
 * (`STA-1`).
 */
class ControlServiceChoiceTest {
    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecModuleCheckAutoConfiguration::class.java,
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                HttpHandlerAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
                CoSecControlServicePortAutoConfiguration::class.java,
                CoSecControlClientAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            "cosec.ip2region.enabled=false",
            "cosec.authorization.routes.location=",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    private val host = "${ControlServiceChoice.KEY}=http://control:8082/cosec/control/v1"

    private fun message(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `an instance that runs the control plane gets the in-process ControlService - STA-11`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(ControlService::class.java).assert().isInstanceOf(LocalControlService::class.java)
        }
    }

    @Test
    fun `an instance that names a control-plane host gets the HTTP ControlService - STA-11`() {
        runner.withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=false", host).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(ControlService::class.java).values.single()
                .assert().isInstanceOf(HttpControlService::class.java)
        }
    }

    @Test
    fun `an instance that neither runs the control plane nor names a host has no ControlService - STA-11`() {
        runner.withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=false").run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(ControlService::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `an instance that runs the control plane and names a host does not start - STA-11`() {
        runner.withPropertyValues(host).run { context ->
            message(context.startupFailure).assert()
                .contains("STA-11")
                .contains(ControlServiceChoice.KEY)
                .contains("one ControlService")
        }
    }

    @Test
    fun `a host named without cosec-control-http fails naming the artifact - STA-1`() {
        runner.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER, ControlServiceChoice.HTTP_SERVICE))
            .withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=false", host)
            .run { context ->
                message(context.startupFailure).assert()
                    .contains("cosec-control-http")
                    .contains(ControlServiceChoice.KEY)
                    .contains("STA-1")
            }
    }
}
