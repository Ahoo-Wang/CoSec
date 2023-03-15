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
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class CoSecMonoTraceTest {
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
    fun traceWithoutSecurityContext() {
        val exchange = mockk<ServerWebExchange> {
            every { getSecurityContext() } returns null
        }

        CoSecMonoTrace(exchange, Mono.empty()).test()
            .verifyComplete()
    }

    @Test
    fun traceWithSecurityContext() {
        val exchange = mockk<ServerWebExchange> {
            every { getSecurityContext() } returns SimpleSecurityContext.anonymous()
        }

        CoSecMonoTrace(exchange, Mono.empty()).test()
            .verifyComplete()
    }

    @Test
    fun trace() {
        val verifyContext = mockk<VerifyContext> {
            every { policy.id } returns "policyId"
            every { statementIndex } returns 1
            every { statement.name } returns "statementName"
            every { result } returns VerifyResult.IMPLICIT_DENY
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setVerifyContext(verifyContext)
        val exchange = mockk<ServerWebExchange> {
            every { getSecurityContext() } returns securityContext
        }

        CoSecMonoTrace(exchange, Mono.empty()).test()
            .verifyComplete()
    }

    @Test
    fun traceWithoutVerifyContext() {
        val securityContext = SimpleSecurityContext.anonymous()
        val exchange = mockk<ServerWebExchange> {
            every { getSecurityContext() } returns securityContext
        }

        CoSecMonoTrace(exchange, Mono.empty()).test()
            .verifyComplete()
    }
}
