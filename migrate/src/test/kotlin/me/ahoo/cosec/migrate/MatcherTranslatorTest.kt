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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MatcherTranslatorTest {
    private val rejections = ArrayList<Rejection>()
    private val translator =
        MatcherTranslator { kind, location, message -> rejections += Rejection(kind, location, message) }

    private fun action(json: String) = translator.action(CoSecJsonSerializer.readTree(json), "a")

    private fun condition(json: String?) = translator.condition(json?.let { CoSecJsonSerializer.readTree(it) }, "c")

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

    @Test
    fun `only no condition and all translate`() {
        condition(null).assert().isTrue()
        condition("null").assert().isTrue()
        condition("""{"all":{}}""").assert().isTrue()
        condition("""{"bool":{"and":[{"all":{}}],"or":[{"all":{}}]}}""").assert().isTrue()
        rejections.assert().isEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"all":{"negate":true}}|CONDITION_NOT_TRANSLATED|c.all""",
            """{"bool":{"negate":true,"and":[{"all":{}}]}}|CONDITION_NOT_TRANSLATED|c.bool""",
            """{"inTenant":{"value":"default"}}|CONDITION_NOT_TRANSLATED|c.inTenant""",
            """{"bool":{"or":[{"all":{}},{"ognl":{"value":"true"}}]}}|EXPRESSION_CONDITION|c.bool.or[1].ognl""",
            """{"mine":{}}|CUSTOM_CONDITION|c.mine""",
            """{"a":{},"b":{}}|UNDECODABLE|c""",
            """[]|UNDECODABLE|c""",
        ],
    )
    fun `every other condition is rejected at its location`(json: String, kind: RejectionKind, location: String) {
        condition(json).assert().isFalse()
        rejections.single().kind.assert().isEqualTo(kind)
        rejections.single().location.assert().isEqualTo(location)
    }
}
