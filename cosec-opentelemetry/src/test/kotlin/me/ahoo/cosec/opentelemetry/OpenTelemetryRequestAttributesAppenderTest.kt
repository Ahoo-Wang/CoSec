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

import io.opentelemetry.sdk.trace.SdkTracerProvider
import me.ahoo.cosec.api.audit.AuditTrace
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.URI

class OpenTelemetryRequestAttributesAppenderTest {
    private val appender = OpenTelemetryRequestAttributesAppender()

    @Test
    fun appendCurrentSpan() {
        val tracerProvider = SdkTracerProvider.builder().build()
        val span = tracerProvider.get("test").spanBuilder("request").startSpan()
        val request = TestRequest()

        val appended = span.makeCurrent().use {
            appender.append(request)
        }

        appended.attributes[AuditTrace.TRACE_ID_ATTRIBUTE_KEY].assert().isEqualTo(span.spanContext.traceId)
        appended.attributes[AuditTrace.SPAN_ID_ATTRIBUTE_KEY].assert().isEqualTo(span.spanContext.spanId)
        span.end()
        tracerProvider.close()
    }

    @Test
    fun invalidSpanKeepsRequest() {
        val request = TestRequest()

        appender.append(request).assert().isSameAs(request)
    }

    private data class TestRequest(
        override val attributes: Map<String, String> = emptyMap(),
    ) : Request {
        override val path: String = "/"
        override val method: String = "GET"
        override val remoteIp: String = "127.0.0.1"
        override val origin: URI = URI.create("")
        override val referer: URI = URI.create("")

        override fun getHeader(key: String): String = ""

        override fun getQuery(key: String): String = ""

        override fun getCookieValue(name: String): String = ""

        override fun withAttributes(attributes: Map<String, String>): Request = copy(attributes = attributes)
    }
}
