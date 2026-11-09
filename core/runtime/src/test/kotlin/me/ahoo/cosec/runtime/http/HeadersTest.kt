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
package me.ahoo.cosec.runtime.http

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class HeadersTest {
    @Test
    fun `names are case-insensitive and values keep the order received`() {
        val headers = Headers.of(
            linkedMapOf(
                "Accept" to listOf("application/json", "text/html"),
                "X-Trace" to listOf("1"),
                "ACCEPT" to listOf("text/plain"),
                "accept" to listOf("*/*"),
            ),
        )
        headers.values("accept").assert().containsExactly("application/json", "text/html", "text/plain", "*/*")
        headers.values("ACCEPT").assert().isEqualTo(headers.values("Accept"))
        headers.values("x-trace").assert().containsExactly("1")
    }

    @Test
    fun `an absent name has no values, a name without values has none`() {
        val headers = Headers.of(mapOf("Empty" to emptyList(), "Other" to listOf("v")))
        headers.values("missing").assert().isEmpty()
        headers.values("empty").assert().isEmpty()
        Headers.EMPTY.values("any").assert().isEmpty()
    }

    @Test
    fun `what the caller changes afterwards does not reach the headers`() {
        val cookie = mutableListOf("a=1")
        val source = linkedMapOf("Cookie" to cookie as List<String>, "Cookie".uppercase() to mutableListOf("b=2"))
        val headers = Headers.of(source)
        cookie += "c=3"
        source["Added"] = listOf("late")
        source.remove("Cookie")
        headers.values("cookie").assert().containsExactly("a=1", "b=2")
        headers.values("added").assert().isEmpty()
    }

    @Test
    fun `a name that is not repeated keeps a copy, not the caller's list`() {
        val values = mutableListOf("x", "y")
        val headers = Headers.of(mapOf("Via" to values))
        values.clear()
        headers.values("via").assert().containsExactly("x", "y")
    }
}
