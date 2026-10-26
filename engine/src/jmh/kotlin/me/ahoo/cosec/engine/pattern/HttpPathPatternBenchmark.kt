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

package me.ahoo.cosec.engine.pattern

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * [HttpPathPattern] against Spring's `PathPattern` (RFC 0005 §9.3): `spring*` is how 7.x matches a `path` action,
 * `cosec*` how the engine will. Both parse the request path once per request, so `*Parse` and `*Match` are separate.
 *
 * Scenarios, each a pattern and a path it matches: `literal`, `capture` (one `{id}`), `glob` (`{name}.{ext}`, a regular
 * expression in Spring), `rest` (a trailing `**`), `leading` (`**` followed by `*.css`), `miss`, a capture pattern whose
 * last literal differs, and `long`, a glob of six `*` that misses one 8 KB segment (review F4 of #1040: a cost that
 * grows with the segment's length squared would show here; Spring's regular expression does not finish, so only the
 * `cosec*` benchmarks run on it).
 */
@State(Scope.Benchmark)
open class HttpPathPatternBenchmark {
    @Param("literal", "capture", "glob", "rest", "leading", "miss", "long")
    lateinit var scenario: String

    private lateinit var path: String
    private lateinit var springPattern: PathPattern
    private lateinit var springPath: PathContainer
    private lateinit var cosecPattern: HttpPathPattern
    private lateinit var cosecPath: HttpPath

    @Setup
    fun setup() {
        val (pattern, path) = when (scenario) {
            "literal" -> "/api/orders/items" to "/api/orders/items"
            "capture" -> "/api/orders/{orderId}/items" to "/api/orders/12345/items"
            "glob" -> "/files/{name}.{ext}" to "/files/report.2026.pdf"
            "rest" -> "/api/orders/**" to "/api/orders/12345/items/6"
            "leading" -> "/**/*.css" to "/static/theme/site.css"
            "miss" -> "/api/orders/{orderId}/items" to "/api/orders/12345/lines"
            "long" -> "/files/*a*a*a*a*a*a*b" to "/files/" + "a".repeat(LONG_SEGMENT)
            else -> error("Unknown scenario $scenario")
        }
        this.path = path
        springPattern = PathPatternParser.defaultInstance.parse(pattern)
        springPath = PathContainer.parsePath(path)
        cosecPattern = HttpPathPattern.compile(pattern)
        cosecPath = HttpPath.parse(path)
        val matches = scenario != "miss" && scenario != "long"
        // Spring's regular expression backtracks without bound on `long`: run only the cosec benchmarks there.
        check((scenario == "long" || springMatch() != null == matches) && cosecMatch() != null == matches)
    }

    @Benchmark
    fun springParse(): PathContainer = PathContainer.parsePath(path)

    @Benchmark
    fun cosecParse(): HttpPath = HttpPath.parse(path)

    @Benchmark
    fun springMatch(): PathPattern.PathMatchInfo? = springPattern.matchAndExtract(springPath)

    @Benchmark
    fun cosecMatch(): Captures? = cosecPattern.match(cosecPath)

    @Benchmark
    fun springMatches(): Boolean = springPattern.matches(springPath)

    @Benchmark
    fun cosecMatches(): Boolean = cosecPattern.matches(cosecPath)

    private companion object {
        const val LONG_SEGMENT = 8192
    }
}
