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

package me.ahoo.cosec.spi.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** A stored value quoted in a message a host logs: escaped, bounded, never cut inside a surrogate pair. */
class SnapshotKeyTextTest {
    @Test
    fun `a short value is escaped, a long one cut saying how long it was`() {
        SnapshotKeyText.quoted("a\nb").assert().isEqualTo("a\\u000Ab")
        val long = "x".repeat(SnapshotKeyText.MAX_QUOTED + 1)
        SnapshotKeyText.quoted(long).assert()
            .isEqualTo("x".repeat(SnapshotKeyText.MAX_QUOTED) + "… (${long.length} characters)")
        SnapshotKeyText.message(
            "m".repeat(SnapshotKeyText.MAX_MESSAGE)
        ).length.assert().isEqualTo(SnapshotKeyText.MAX_MESSAGE)
        SnapshotKeyText.message("m\n".repeat(SnapshotKeyText.MAX_MESSAGE)).none { it.isISOControl() }.assert().isTrue()
    }

    @Test
    fun `a cut never leaves half of a surrogate pair`() {
        // A pair starts at the last character kept: the cut goes before it.
        val text = "a".repeat(SnapshotKeyText.MAX_QUOTED - 1) + "😀" + "b"
        val quoted = SnapshotKeyText.quoted(text)
        quoted.assert().startsWith("a".repeat(SnapshotKeyText.MAX_QUOTED - 1) + "…")
        quoted.indices.none { Identifiers.isUnpairedSurrogateAt(quoted, it) }.assert().isTrue()
    }

    @Test
    fun `names are quoted, at most a few and how many more`() {
        SnapshotKeyText.names(listOf("a", "b\n")).assert().isEqualTo("[a, b\\u000A]")
        SnapshotKeyText.names(listOf("a", "b", "c", "d", "e")).assert().isEqualTo("[a, b, c] and 2 more")
    }
}
