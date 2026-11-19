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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.identity.IdentityRefusals
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `OBS-7`: `cosec.identity.refused` counts every forwarded identity a service in gateway mode refused, by problem. */
class IdentityRefusedMetricTest {
    @Test
    fun `each refusal counts in the series of its problem, and the series are the gate's problems only`() {
        val registry = SimpleMeterRegistry()
        val refusals = IdentityRefusals()
        MetricsListener.bind(registry, refusals)
        val gate = IdentityGate(refusals = refusals)
        repeat(2) {
            runBlocking {
                gate.evaluate(
                    HttpFacts(
                        "GET",
                        "/orders",
                        null,
                        Headers.of(mapOf(IdentityCodec.HEADER to listOf("not base64!"))),
                        Cookies.EMPTY,
                        "10.0.0.1",
                        HostScope(null),
                    )
                )
            }
        }
        val series = registry.find(MetricsListener.IDENTITY_REFUSED).functionCounters()
        series.associate { it.id.getTag(MetricsListener.PROBLEM_TAG) to it.count() }.assert().isEqualTo(
            mapOf(
                ProblemType.INVALID_REQUEST.slug to 2.0,
                ProblemType.TOKEN_INVALID.slug to 0.0,
                ProblemType.TOKEN_EXPIRED.slug to 0.0,
            )
        )
    }
}
