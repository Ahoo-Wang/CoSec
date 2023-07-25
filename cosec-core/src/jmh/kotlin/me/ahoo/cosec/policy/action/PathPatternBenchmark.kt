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

package me.ahoo.cosec.policy.action

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

@State(Scope.Benchmark)
open class PathPatternBenchmark {
    companion object {
        const val path = "/api/user/CoSecId"
    }

    private lateinit var patternParser: PathPatternParser
    private lateinit var pathPattern: PathPattern

    @Setup
    fun init() {
        patternParser = PathPatternParser.defaultInstance
        pathPattern = patternParser.parse("/api/user/{id}")
    }

    @Benchmark
    fun matches(): Boolean {
        PathContainer.parsePath(path, patternParser.pathOptions)
            .let { pathContainer ->
                return pathPattern.matches(pathContainer)
            }
    }

    @Benchmark
    fun matchAndExtract(): PathPattern.PathMatchInfo? {
        PathContainer.parsePath(path, patternParser.pathOptions)
            .let { pathContainer ->
                return pathPattern.matchAndExtract(pathContainer)
            }
    }

}
