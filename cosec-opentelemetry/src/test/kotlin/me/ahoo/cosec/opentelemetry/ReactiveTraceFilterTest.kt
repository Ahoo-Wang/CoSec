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

package me.ahoo.cosec.opentelemetry

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ReactiveTraceFilterTest {
    companion object {
        val tracer: Tracer by lazy {
            GlobalOpenTelemetry.getTracer("test")
        }

        init {
            val sdkTracerProvider: SdkTracerProvider = SdkTracerProvider.builder()
                .build()
            OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal()
        }
    }

    @Test
    fun filterWithError() {
        val span = tracer.spanBuilder("test").startSpan()
        try {
            span.makeCurrent().use {
                val exchange = mockk<ServerWebExchange>() {
                    every { getSecurityContext() } throws RuntimeException("test")
                }
                ReactiveTraceFilter.filter(exchange) {
                    Mono.empty()
                }.test()
                    .verifyComplete()
            }
        } finally {
            span.end()
        }
    }

    @Test
    fun filterWithoutSecurityContext() {
        val exchange = mockk<ServerWebExchange>() {
            every { getSecurityContext() } returns null
        }

        ReactiveTraceFilter.filter(exchange) {
            Mono.empty()
        }.test()
            .verifyComplete()
    }

    @Test
    fun filterWithSecurityContext() {
        val exchange = mockk<ServerWebExchange>() {
            every { getSecurityContext() } returns SimpleSecurityContext.ANONYMOUS
        }

        ReactiveTraceFilter.filter(exchange) {
            Mono.empty()
        }.test()
            .verifyComplete()
    }

    @Test
    fun filter() {
        val span = tracer.spanBuilder("test").startSpan()
        try {
            assertThat(span.isRecording, `is`(true))
            span.makeCurrent().use {
                val exchange = mockk<ServerWebExchange>() {
                    every { getSecurityContext() } returns SimpleSecurityContext.ANONYMOUS
                }

                ReactiveTraceFilter.filter(exchange) {
                    Mono.empty()
                }.test()
                    .verifyComplete()

                val attributesField = span.javaClass.declaredFields.first {
                    it.name == "attributes"
                }
                attributesField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val attributes = attributesField.get(span) as Map<AttributeKey<String>, String>
                assertThat(
                    attributes[SemanticAttributes.ENDUSER_ID],
                    `is`(SimpleSecurityContext.ANONYMOUS.principal.id)
                )
                assertThat(attributes[SemanticAttributes.ENDUSER_ROLE], `is`(""))
                assertThat(
                    attributes[COSEC_TENANT_ID_ATTRIBUTE_KEY],
                    `is`(SimpleSecurityContext.ANONYMOUS.tenant.tenantId)
                )
                assertThat(attributes[COSEC_POLICY_ATTRIBUTE_KEY], `is`(""))
            }
        } finally {
            span.end()
        }
    }
}
