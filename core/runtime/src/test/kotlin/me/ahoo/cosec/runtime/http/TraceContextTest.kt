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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, me.ahoo.cosec.annotations.ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.http

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class TraceContextTest {
    @Test
    fun `a trace without a tracestate has none, and the three-argument constructor stays`() {
        val trace = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true)
        trace.traceState.assert().isEmpty()
        trace.sampled.assert().isTrue()
    }

    /** The tracestate is vendor data: it never appears where a trace is printed. */
    @Test
    fun `the tracestate is opaque and not printed`() {
        val trace = TraceContext("trace-1", "span-1", false, "vendor=opaque-value")
        trace.traceState.assert().isEqualTo("vendor=opaque-value")
        trace.toString().assert().isEqualTo("TraceContext(trace-1/span-1)")
    }
}
