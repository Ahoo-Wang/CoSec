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

package me.ahoo.cosec.gateway

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.lang.management.ManagementFactory

/**
 * `PIP-31` on the hot path: the filter's removal of the three method-override headers allocates nothing when the request
 * carries none of them. Measured on the headers type the forwarding builder hands the filter (a copy in a
 * case-insensitive map), with the names as the filter writes them.
 */
class MethodOverrideAllocationTest {
    private val names = arrayOf("x-http-method-override", "x-http-method", "x-method-override")

    private fun allocated(headers: HttpHeaders, rounds: Int): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        repeat(rounds) {
            for (name in names) {
                headers.remove(name)
            }
        }
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun `removing three absent headers allocates nothing`() {
        val headers = HttpHeaders().apply {
            set("Accept", "*/*")
            set("Authorization", "token")
        }
        allocated(headers, 200_000)
        // After the warm-up: a million lookups, and the thread allocated under a byte per lookup on average.
        allocated(headers, 1_000_000).assert().isLessThan(1_000_000L)
    }
}
