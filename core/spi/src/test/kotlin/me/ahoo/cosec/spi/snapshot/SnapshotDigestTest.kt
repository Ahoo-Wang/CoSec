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

package me.ahoo.cosec.spi.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.Random

/** The text form of a digest (#1217): lowercase, two digits a byte with leading zeros, in byte order. */
class SnapshotDigestTest {
    private fun legacy(bytes: ByteArray): String =
        "SHA-256:" + bytes.joinToString(separator = "") { "%02x".format(it) }

    @Test
    fun `the bytes 0x00, 0x0f, 0x80 and 0xff are written as two lowercase digits each`() {
        val bytes = ByteArray(SnapshotDigest.SHA_256_BYTES) { listOf(0x00, 0x0f, 0x80, 0xff)[it % 4].toByte() }
        val text = SnapshotDigest(bytes).toString()
        text.assert().isEqualTo("SHA-256:" + "000f80ff".repeat(8))
        SnapshotDigest.isDigest(text).assert().isTrue()
        SnapshotDigest.parse(text).assert().isEqualTo(SnapshotDigest(bytes))
    }

    @Test
    fun `the text is what it was written as before, and reads back, for any bytes`() {
        val random = Random(1217)
        repeat(1_000) {
            val bytes = ByteArray(SnapshotDigest.SHA_256_BYTES).also(random::nextBytes)
            val digest = SnapshotDigest(bytes)
            digest.toString().assert().isEqualTo(legacy(bytes))
            SnapshotDigest.parse(digest.toString()).toByteArray().assert().isEqualTo(bytes)
        }
    }
}
