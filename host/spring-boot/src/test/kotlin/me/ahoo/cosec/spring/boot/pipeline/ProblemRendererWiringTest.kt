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
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** `PIP-34`: the starter wires the application's one `ProblemRenderer` into the adapters, CoSec's own body without one. */
class ProblemRendererWiringTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecGatewayModeAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
            )
        )
        .withPropertyValues(
            "cosec.pipeline.mode=gateway",
            "cosec.jwt.enabled=false",
            "cosec.pipeline.identity.only-gateway-can-reach=true",
        )

    private val renderer = ProblemRenderer { """{"type":"urn:host:error:${it.type.code}"}""".toByteArray() }

    /** An identity header that is not one: the gate refuses it, 400, before anything else. */
    private fun refused(filter: PipelineServletFilter): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/orders").apply { addHeader("CoSec-Identity", "not base64!") }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterOf(context: org.springframework.context.ApplicationContext): PipelineServletFilter =
        (context.getBean(FilterRegistrationBean::class.java) as FilterRegistrationBean<PipelineServletFilter>).filter!!

    @Test
    fun `without a renderer a refusal is CoSec's own body`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(ProblemBodies::class.java).renderer.assert().isSameAs(ProblemRenderer.DEFAULT)
            val response = refused(filterOf(context))
            response.status.assert().isEqualTo(400)
            response.contentAsString.assert().startsWith("""{"type":"https://cosec.ahoo.me/problems/invalid-request"""")
        }
    }

    @Test
    fun `the application's renderer writes the body, under CoSec's status and media type`() {
        runner.withBean("hostRenderer", ProblemRenderer::class.java, { renderer }).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(ProblemBodies::class.java).renderer.assert().isSameAs(renderer)
            val response = refused(filterOf(context))
            response.status.assert().isEqualTo(400)
            response.contentType.assert().isEqualTo("application/problem+json")
            response.getHeader("CoSec-Request-Id").assert().isNotNull()
            response.contentAsString.assert().isEqualTo("""{"type":"urn:host:error:INVALID_REQUEST"}""")
        }
    }

    @Test
    fun `two renderers fail startup and name both`() {
        runner.withBean("first", ProblemRenderer::class.java, { renderer })
            .withBean("second", ProblemRenderer::class.java, { renderer })
            .run { context ->
                val message = generateSequence<Throwable>(context.startupFailure) { it.cause }
                    .joinToString(" | ") { it.message.orEmpty() }
                message.assert().contains("PIP-34").contains("2 ProblemRenderer beans").contains("first, second")
            }
    }
}
