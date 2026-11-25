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

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * `Headers.of`, which every web adapter calls once per request (#1213): the headers a browser or a client sends,
 * `headers` of them, one repeated in another case (`Accept` and `ACCEPT`) and one with several values (`Cookie`), as the
 * adapters hand them over. `lookup` is what the pipeline does with them afterwards: it reads the few the documents name.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class HeadersBenchmark {
    @Param("8", "32", "64")
    var headers: Int = 0

    private lateinit var input: Map<String, List<String>>
    private lateinit var built: Headers

    @Setup
    fun setup() {
        val map = LinkedHashMap<String, List<String>>()
        map["Cookie"] = listOf("session=abc", "theme=dark", "lang=en")
        map["Accept"] = listOf("application/json")
        map["ACCEPT"] = listOf("text/plain")
        var n = 0
        while (map.size < headers) {
            map["X-Header-${n++}"] = listOf("value-$n")
        }
        input = map
        built = Headers.of(input)
        check(built.values("accept") == listOf("application/json", "text/plain")) { "Headers folded to ${built.values("accept")}" }
        check(built.values("cookie").size == 3)
    }

    @Benchmark
    fun of(): Headers = Headers.of(input)

    @Benchmark
    fun lookup(): List<String> = built.values("Accept")
}
