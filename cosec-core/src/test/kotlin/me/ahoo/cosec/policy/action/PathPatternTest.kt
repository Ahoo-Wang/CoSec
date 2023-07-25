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

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

class PathPatternTest {
    companion object {
        const val path = "/api/user/CoSecId"
        val patternParser: PathPatternParser = PathPatternParser.defaultInstance
        val pathPattern: PathPattern = patternParser.parse("/api/user/{id}")
        fun matches(): Boolean {
            PathContainer.parsePath(path, patternParser.pathOptions)
                .let { pathContainer ->
                    return pathPattern.matches(pathContainer)
                }
        }

        fun matchAndExtract(): PathPattern.PathMatchInfo? {
            PathContainer.parsePath(path, patternParser.pathOptions)
                .let { pathContainer ->
                    return pathPattern.matchAndExtract(pathContainer)
                }
        }
    }

    @Test
    fun matches() {
        assertThat(PathPatternTest.matches(), equalTo(true))
    }

    @Test
    fun matchAndExtract() {
        val pathMatchInfo = PathPatternTest.matchAndExtract()
        assertThat(pathMatchInfo, notNullValue())
        assertThat(pathMatchInfo!!.uriVariables["id"], equalTo("CoSecId"))
    }
}
