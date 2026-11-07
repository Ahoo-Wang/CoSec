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

package me.ahoo.cosec.model

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** RC1-52, `IDN-10`: the one text rule of every id, and how a message writes an id that breaks it. */
class IdentifiersTest {
    private val unpaired = listOf(
        "\uD800",
        "\uDC00",
        "beta\uD800",
        "\uDBFFbeta",
        "a\uDC00\uD800b",
        "\uD83D\uD83D\uDE00"
    )
    private val wellFormed = listOf("", "beta", "beta?", "\uD83D\uDE00", "a\uD83D\uDE00b", "é", "\u0000")

    @Test
    fun `an unpaired surrogate anywhere makes the text ill formed`() {
        unpaired.forEach { Identifiers.isWellFormed(it).assert().describedAs(it).isFalse() }
        wellFormed.forEach { Identifiers.isWellFormed(it).assert().describedAs(it).isTrue() }
        Identifiers.areWellFormed(wellFormed).assert().isTrue()
        Identifiers.areWellFormed(wellFormed + "x\uD800").assert().isFalse()
    }

    /** The rule is exactly UTF-8's: a text is well formed when it encodes to UTF-8 and back unchanged. */
    @Test
    fun `well formed is what survives UTF-8`() {
        val random = Random(52)
        val alphabet = "ab?\uD800\uDBFF\uDC00\uDFFF\uD83D\uDE00"
        repeat(ITERATIONS) {
            val text = (0 until random.nextInt(0, 6)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val survives = text.encodeToByteArray().decodeToString() == text
            Identifiers.isWellFormed(text).assert().describedAs(Identifiers.printable(text)).isEqualTo(survives)
        }
    }

    @Test
    fun `memberships are well formed when every id they hold is`() {
        fun memberships(space: String = "", role: String = "admin", policy: String = "p", malformed: String? = null) =
            Memberships(
                Memberships.Source.PROVIDER,
                mapOf(SpaceId(space) to setOf(RoleId(role))),
                listOf(PolicyId(policy)),
                listOfNotNull(malformed),
            )
        Identifiers.isWellFormed(memberships()).assert().isTrue()
        Identifiers.isWellFormed(memberships(space = "team\uD800")).assert().isFalse()
        Identifiers.isWellFormed(memberships(role = "admin\uDC00")).assert().isFalse()
        Identifiers.isWellFormed(memberships(policy = "\uD800")).assert().isFalse()
        Identifiers.isWellFormed(memberships(malformed = "a@b@\uD800")).assert().isFalse()
    }

    @Test
    fun `a message writes an unpaired surrogate and a control character as escapes, never as a question mark`() {
        Identifiers.printable("beta\uD800").assert().isEqualTo("beta\\uD800")
        Identifiers.printable("\uDC00x").assert().isEqualTo("\\uDC00x")
        Identifiers.printable("a\nforged").assert().isEqualTo("a\\u000Aforged")
        Identifiers.printable("\uD83D\uDE00?").assert().isEqualTo("\uD83D\uDE00?")
        val plain = "beta?"
        Identifiers.printable(plain).assert().isSameAs(plain)
        // Two ids that would share a Redis key are told apart in a log line.
        Identifiers.printable("beta\uD800").assert().isNotEqualTo(Identifiers.printable("beta?"))
    }

    private companion object {
        const val ITERATIONS = 5_000
    }
}
