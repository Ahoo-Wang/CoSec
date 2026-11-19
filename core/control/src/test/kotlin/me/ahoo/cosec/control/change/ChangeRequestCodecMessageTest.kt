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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, me.ahoo.cosec.annotations.ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.change

import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A stored value that does not decode is logged by whoever reads it (the control API logs it at ERROR): its message
 * quotes nothing of the value unescaped or unbounded, and carries no parser exception, whose message quotes the bytes
 * (#1691's rule for the identity header, here for stored values).
 */
class ChangeRequestCodecMessageTest {
    private fun refused(text: String): SnapshotFormatException =
        assertThrows<SnapshotFormatException> { ChangeRequestCodec.decode(text.encodeToByteArray()) }

    private fun SnapshotFormatException.isSafe() {
        val text = message.orEmpty()
        text.none { it.isISOControl() }.assert().describedAs(text).isTrue()
        text.length.assert().isLessThan(SnapshotKeyText.MAX_QUOTED * (SnapshotKeyText.MAX_NAMES + 2))
        cause.assert().isNull()
    }

    @Test
    fun `bytes that are not JSON are named by where they fail, never by what they hold`() {
        val refused = refused("{\"x\n2026-11-19 ERROR forged\u0007" + "y".repeat(5_000))
        refused.isSafe()
        refused.message.assert().matches("A change request is not JSON at line \\d+, column \\d+\\.")
    }

    @Test
    fun `unknown members are quoted escaped, bounded and at most three`() {
        // JSON escapes: the decoded name holds the line break and the bell.
        val long = "x\\n2026-11-19 ERROR forged\\u0007" + "y".repeat(5_000)
        val refused = refused("""{"$long":1,"a\u0001":2,"b":3,"c":4,"d":5}""")
        refused.isSafe()
        refused.message.assert()
            .contains("x\\u000A2026-11-19 ERROR forged\\u0007").contains(" characters)").contains("and 2 more")
    }

    @Test
    fun `a value of the wrong type is quoted escaped and bounded`() {
        refused("[\"x\\u0007" + "y".repeat(5_000) + "\"]").isSafe()
    }
}
