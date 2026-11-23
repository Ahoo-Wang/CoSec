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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random

/** [Sha256] is SHA-256: the FIPS 180-4 vectors, and `MessageDigest` over every length around the block boundaries. */
class Sha256Test {
    @Test
    fun `the FIPS vectors`() {
        Sha256.hex(ByteArray(0)).assert()
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        Sha256.hex("abc".encodeToByteArray()).assert()
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).assert()
            .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    @Test
    fun `what MessageDigest answers, for every length up to four blocks and random long inputs`() {
        val random = Random(SEED)
        val lengths = (0..4 * 64 + 1).toList() + List(CASES) { random.nextInt(MAX_LENGTH) }
        for (length in lengths) {
            val input = ByteArray(length).also(random::nextBytes)
            val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input))
            Sha256.hex(input).assert().describedAs("length $length").isEqualTo(expected)
        }
    }

    private companion object {
        const val SEED = 180L
        const val CASES = 200
        const val MAX_LENGTH = 20_000
    }
}
