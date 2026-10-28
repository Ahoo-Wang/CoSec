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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The condition syntax (RFC 0005 §3.1): every form reads back as it was written. */
@Suppress("MaxLineLength")
class ConditionJsonTest {
    private fun read(text: String): Condition = ConditionJson.read(
        DocumentNode(CoSecJsonSerializer.readTree(text), "$")
    )

    private fun roundTrip(text: String) {
        val written = ConditionJson.write(read(text)).toString()
        written.assert().isEqualTo(CoSecJsonSerializer.readTree(text).toString())
        ConditionJson.write(read(written)).toString().assert().isEqualTo(written)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"eq":["subject.id","alice"]}""",
            """{"eq":["subject.id","Alice"],"ignoreCase":true}""",
            """{"eq":["subject.authenticated",true]}""",
            """{"eq":["resource.var.id",{"attr":"subject.id"}]}""",
            """{"prefix":["scope.app","ord"]}""",
            """{"prefix":["scope.app","Ord"],"ignoreCase":true}""",
            """{"in":["scope.space",["a","b"]]}""",
            """{"in":["admin","scope.roles"]}""",
            """{"cidr":["environment.ip",["10.0.0.0/8","::1/128"]]}""",
            """{"time":{"notBefore":"2026-01-01T00:00:00Z","notAfter":"2027-01-01T00:00:00Z","zone":"UTC","daysOfWeek":["MON","FRI"],"from":"09:00","to":"18:00"}}""",
            """{"time":{"notAfter":"2027-01-01T00:00:00Z"}}""",
            """{"exists":"http.header.x-trace"}""",
            """{"and":[{"exists":"a"},{"or":[{"exists":"b"},{"not":{"exists":"c"}}]}]}""",
        ],
    )
    fun `every form reads back as written`(text: String) {
        roundTrip(text)
    }

    @Test
    fun `operands keep their types`() {
        (read("""{"eq":["x",{"attr":"y"}]}""") as Condition.Eq).value.assert().isInstanceOf(Operand.Attr::class.java)
        (read("""{"eq":["x",false]}""") as Condition.Eq).value.assert().isEqualTo(Literal.Bool(false))
        (
            read(
                """{"in":["x",[true,"a"]]}"""
            ) as Condition.In
            ).values.assert().containsExactly(Literal.Bool(true), Literal.Str("a"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{}""",
            """{"or":[]}""",
            """{"prefix":["a",true]}""",
            """{"cidr":["a",[1]]}""",
            """{"in":[true,"scope.roles"]}""",
            """{"exists":1}""",
            """{"not":[]}""",
            """{"eq":"a"}""",
            """{"time":{"daysOfWeek":"MON"}}""",
            """{"eq":["a",null]}""",
            """{"eq":["a",1.5]}""",
            """[]""",
        ],
    )
    fun `what is not the syntax is refused`(text: String) {
        assertThrownBy<SnapshotFormatException> { read(text) }
    }

    @Test
    fun `the reader names what it found`() {
        val node = { text: String -> DocumentNode(CoSecJsonSerializer.readTree(text), "$.x") }
        assertThrownBy<SnapshotFormatException> {
            node("1.5").long()
        }.hasMessageContaining("$.x: expected an integer, found a number")
        assertThrownBy<SnapshotFormatException> { node("null").text() }.hasMessageContaining("found null")
        assertThrownBy<SnapshotFormatException> { node("{}").bool() }.hasMessageContaining("found an object")
        assertThrownBy<SnapshotFormatException> { node("true").elements() }.hasMessageContaining("found a boolean")
        assertThrownBy<SnapshotFormatException> { node("[]").names() }.hasMessageContaining("found an array")
        assertThrownBy<SnapshotFormatException> {
            node("""{"a":1,"b":2}""").requireObject()
        }.hasMessageContaining("unknown fields a, b")
        node("[\"a\"]").strings().assert().containsExactly("a")
        node("7").long().assert().isEqualTo(7)
    }
}
