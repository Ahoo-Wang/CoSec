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

import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.action.PathPatternParsers.asPathPatternParser
import me.ahoo.cosec.policy.condition.part.PathConditionMatcherFactory
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.web.util.pattern.PathPatternParser

class PathPatternParsersTest {

    @Test
    fun asPathPatternParserWhenPathIsNull() {
        assertThat(
            mapOf<String, String>().asConfiguration().asPathPatternParser(),
            equalTo(PathPatternParser.defaultInstance)
        )
    }

    @Test
    fun asPathPatternParser() {
        val pathPatternParser = mapOf(
            PathConditionMatcherFactory.TYPE to mapOf<String, Any>(
                "caseSensitive" to false,
                "separator" to ".",
                "decodeAndParseSegments" to false
            )
        ).asConfiguration().asPathPatternParser()
        assertThat(pathPatternParser.isCaseSensitive, equalTo(false))
        assertThat(pathPatternParser.pathOptions.separator(), equalTo('.'))
        assertThat(pathPatternParser.pathOptions.shouldDecodeAndParseSegments(), equalTo(false))
    }


    @Test
    fun asPathPatternParserWhenDefault() {
        val pathPatternParser = mapOf(
            PathConditionMatcherFactory.TYPE to mapOf<String, Any>(
            )
        ).asConfiguration().asPathPatternParser()
        assertThat(pathPatternParser.isCaseSensitive, equalTo(PathPatternParser.defaultInstance.isCaseSensitive))
        assertThat(
            pathPatternParser.pathOptions.separator(),
            equalTo(PathPatternParser.defaultInstance.pathOptions.separator())
        )
        assertThat(
            pathPatternParser.pathOptions.shouldDecodeAndParseSegments(),
            equalTo(PathPatternParser.defaultInstance.pathOptions.shouldDecodeAndParseSegments())
        )
    }
}