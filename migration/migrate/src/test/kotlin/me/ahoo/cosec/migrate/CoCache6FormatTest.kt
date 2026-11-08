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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** CoCache 6's format 1, as 7.x data is stored: anything else fails the read, never reads as absent. */
class CoCache6FormatTest {
    private val payload = """{"id":"p"}""".encodeToByteArray()

    @Test
    fun `a value reads as its payload, an absence or a lease as no value`() {
        CoCache6Format.scalar("k", "string") { CoCache6.value(payload) }!!.decodeToString()
            .assert().isEqualTo("""{"id":"p"}""")
        CoCache6Format.scalar(
            "k",
            "string"
        ) { CoCache6.value(ByteArray(0), CoCache6Format.STATE_MISSING) }.assert().isNull()
        CoCache6Format.scalar(
            "k",
            "string"
        ) { CoCache6.value(ByteArray(0), CoCache6Format.STATE_LEASE) }.assert().isNull()
        CoCache6Format.scalar("k", "none") { error("not read") }.assert().isNull()
        // Deleted between TYPE and GET.
        CoCache6Format.scalar("k", "string") { null }.assert().isNull()
    }

    @Test
    fun `anything that is not a format 1 value fails, naming its key`() {
        val unreadable = listOf(
            "plain".encodeToByteArray(),
            CoCache6.value(payload).also { it[1] = 2 },
            CoCache6.value(payload, 'X'.code.toByte()),
        )
        unreadable.forEach { raw ->
            assertThrows<IllegalStateException> { CoCache6Format.scalar("cosec:policy:p", "string") { raw } }
                .message.assert().contains("cosec:policy:p")
        }
        assertThrows<IllegalStateException> { CoCache6Format.scalar("k", "set") { error("not read") } }
    }

    @Test
    fun `a set reads as its members`() {
        val fields = CoCache6.set("a", "b")
        CoCache6Format.members("k", "hash") { fields.keys.toList() to fields[CoCache6Format.SET_FORMAT_FIELD] }
            .map { it.decodeToString() }.assert().containsExactlyInAnyOrder("a", "b")
        CoCache6Format.members("k", "none") { error("not read") }.assert().isEmpty()
        assertThrows<IllegalStateException> {
            CoCache6Format.members("k", "hash") { fields.keys.toList() to "2".encodeToByteArray() }
        }
        assertThrows<IllegalStateException> { CoCache6Format.members("k", "hash") { emptyList<ByteArray>() to null } }
        // A 5.x native set.
        assertThrows<IllegalStateException> { CoCache6Format.members("k", "set") { error("not read") } }
    }
}
