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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MatcherTranslatorTest {
    private val rejections = ArrayList<Rejection>()
    private val translator =
        MatcherTranslator { kind, location, message -> rejections += Rejection(kind, location, message) }

    private fun action(json: String) = translator.action(CoSecJson.mapper.readTree(json), "a")

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """"*"|*|""",
            """["/a","*"]|*|""",
            """{"all":"*"}|*|""",
            """{"path":["*"]}|*|""",
            """{"composite":["/a","*"]}|*|""",
            """"/orders/**"|http:*|http:path:/orders/**""",
            """{"path":"/orders"}|http:*|http:path:/orders""",
            """{"path":["/a","/b"]}|http:*|http:path:/a http:path:/b""",
            """{"path":{"pattern":["/a","/b"],"method":"post"}}|http:POST|http:path:/a http:path:/b""",
            """{"all":{"method":["GET","get"]}}|http:GET|""",
            """{"composite":[{"path":{"pattern":"/a","method":"GET"}},{"path":{"pattern":"/b","method":"get"}}]}|http:GET|http:path:/a http:path:/b""",
        ],
    )
    fun `actions translate exactly`(json: String, actions: String, resources: String?) {
        val action = action(json)!!
        rejections.assert().isEmpty()
        action.actions.joinToString(" ").assert().isEqualTo(actions)
        action.resources.joinToString(" ") { it.pattern }.assert().isEqualTo(resources.orEmpty())
    }

    @Test
    fun `path options carry over to the resource pattern`() {
        val resource = action(
            """{"path":{"pattern":"/A","options":{"caseSensitive":false,"decodeAndParseSegments":false}}}"""
        )!!
            .resources.single()
        resource.caseSensitive.assert().isFalse()
        resource.decodeAndParseSegments.assert().isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"path":{"pattern":"/a","options":{"separator":"."}}}|ACTION_NOT_TRANSLATED|a.path.options.separator""",
            """"/users/#{principal.id}"|ACTION_NOT_TRANSLATED|a""",
            """["/a",{"path":{"pattern":"/b","method":"GET"}}]|ACTION_NOT_TRANSLATED|a""",
            """[{"all":{"method":"GET"}},"/b"]|ACTION_NOT_TRANSLATED|a""",
            """{"custom":{}}|CUSTOM_ACTION|a""",
            // Review M1: 7.x compares methods literally; `*` and blanks match no request, so they must not become http:*.
            """{"path":{"pattern":"/orders/**","method":"*"}}|ACTION_NOT_TRANSLATED|a.path.method""",
            """{"all":{"method":" "}}|ACTION_NOT_TRANSLATED|a.all.method""",
            """{"path":{"pattern":"/a","method":["GET",""]}}|ACTION_NOT_TRANSLATED|a.path.method""",
            """{"composite":"/a"}|UNDECODABLE|a""",
            """{"path":{"method":"GET"}}|UNDECODABLE|a.path""",
            """{"path":{"pattern":[1]}}|UNDECODABLE|a.path.pattern""",
            """{"path":{"pattern":"/a","method":[1]}}|UNDECODABLE|a.path.method""",
            """{"path":[1]}|UNDECODABLE|a.path""",
            """{"path":1}|UNDECODABLE|a.path""",
            """[]|UNDECODABLE|a""",
            "1|UNDECODABLE|a",
        ],
    )
    fun `actions that do not translate exactly are rejected at their location`(
        json: String,
        kind: RejectionKind,
        location: String
    ) {
        action(json).assert().isNull()
        rejections.last().kind.assert().isEqualTo(kind)
        rejections.last().location.assert().isEqualTo(location)
    }
}
