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

import me.ahoo.cosec.api.configuration.Configuration
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

internal object PathPatternParsers {
    const val OPTIONS_KEY = "options"

    fun Configuration.asPathPatternParser(): PathPatternParser {
        val pathOptions = get(OPTIONS_KEY) ?: return PathPatternParser.defaultInstance
        val caseSensitive =
            pathOptions.get("caseSensitive")?.asBoolean() ?: PathPatternParser.defaultInstance.isCaseSensitive
        val separator = pathOptions.get("separator")?.asString()?.trim()
            ?: PathPatternParser.defaultInstance.pathOptions.separator().toString()
        require(separator.length == 1) {
            "separator must be a single character."
        }
        val decodeAndParseSegments = pathOptions.get("decodeAndParseSegments")?.asBoolean()
            ?: PathPatternParser.defaultInstance.pathOptions.shouldDecodeAndParseSegments()
        val parser = PathPatternParser()
        parser.isCaseSensitive = caseSensitive
        parser.pathOptions = PathContainer.Options.create(separator[0], decodeAndParseSegments)
        return parser
    }
}
