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

package me.ahoo.cosec.spring.boot

import io.mockk.mockk
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `STA-1` for what a feature needs beyond its module: the gate of a web host (DR1-G-6), and a control plane that is
 * wired where it is switched on (DR1-G-5).
 */
class WebGateCheckTest {
    private val config = AutoConfigurations.of(CoSecModuleCheckAutoConfiguration::class.java)
    private val redis = { mockk<ReactiveStringRedisTemplate>(relaxed = true) }
    private val runtime = { mockk<CacheRuntime>(relaxed = true) }
    private val servlet = WebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)
    private val reactive = ReactiveWebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)
    private val plain = ApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)

    private fun failsSaying(runner: AbstractApplicationContextRunner<*, *, *>, vararg parts: String) {
        runner.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            val message = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            parts.forEach { message.assert().contains(it) }
        }
    }

    private fun starts(runner: AbstractApplicationContextRunner<*, *, *>) {
        runner.run { context -> context.startupFailure.assert().describedAs("${context.startupFailure}").isNull() }
    }

    @Test
    fun `a web host with CoSec on and its adapter's module, but no gate, fails startup`() {
        failsSaying(servlet, "registers none of its gates", "STA-1")
        failsSaying(reactive.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER)), "registers none of its gates")
        failsSaying(reactive, "registers none of its gates")
        // A filter of another type registered where CoSec's was is no gate.
        failsSaying(
            servlet.withBean("cosecPipelineServletFilter", FilterRegistrationBean::class.java, {
                FilterRegistrationBean(mockk<OncePerRequestFilter>(relaxed = true))
            }),
            "registers none of its gates",
        )
    }

    @Test
    fun `a gate found by type, whatever its bean's name, lets the host start`() {
        starts(
            servlet.withBean("applicationsOwn", FilterRegistrationBean::class.java, {
                FilterRegistrationBean(mockk<PipelineServletFilter>(relaxed = true))
            })
        )
        starts(servlet.withBean(PipelineServletFilter::class.java, { mockk<PipelineServletFilter>(relaxed = true) }))
        starts(
            reactive.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
                .withBean(PipelineWebFilter::class.java, { mockk<PipelineWebFilter>(relaxed = true) })
        )
        starts(reactive.withBean(PipelineGatewayFilter::class.java, { mockk<PipelineGatewayFilter>(relaxed = true) }))
    }

    @Test
    fun `with CoSec off, or on a host that serves no web requests, no gate is needed`() {
        starts(servlet.withPropertyValues("cosec.enabled=false"))
        starts(plain)
    }

    @Test
    fun `the control plane switched on in gateway mode fails startup, which wires none`() {
        failsSaying(
            plain.withPropertyValues(
                "cosec.control.enabled=true",
                "cosec.pipeline.mode=gateway",
                "cosec.pipeline.identity.only-gateway-can-reach=true",
            ),
            "cosec.control.enabled=true, but cosec.pipeline.mode=gateway",
            "STA-1",
        )
        // Unset in gateway mode: a service behind a gateway, with the classes and nothing asked of them.
        starts(
            plain.withPropertyValues(
                "cosec.pipeline.mode=gateway",
                "cosec.pipeline.identity.only-gateway-can-reach=true",
                "cosec.jwt.enabled=false",
            )
        )
        starts(plain.withPropertyValues("cosec.control.enabled=true"))
    }
}
