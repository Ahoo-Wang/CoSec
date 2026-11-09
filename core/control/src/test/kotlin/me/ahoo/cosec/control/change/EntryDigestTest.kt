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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.change

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Random

/** An entry's digest (#1217) is SHA-256 in lowercase hex, two digits a byte with leading zeros, as it always was. */
class EntryDigestTest {
    @Test
    fun `a digest is the SHA-256 of the entry in lowercase hex`() {
        EntryDigest.hex(ByteArray(0)).assert()
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        EntryDigest.hex("abc".encodeToByteArray()).assert()
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `every byte keeps its leading zero and its order, whatever the input`() {
        val random = Random(1217)
        var leadingZero = 0
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            val expected = hash.joinToString(separator = "") { "%02x".format(it) }
            EntryDigest.hex(bytes).assert().isEqualTo(expected)
            leadingZero += hash.count { it in 0..0x0f }
        }
        // The cases that a hex encoder gets wrong were among them.
        (leadingZero > 0).assert().isTrue()
    }
}
