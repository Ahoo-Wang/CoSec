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

package me.ahoo.cosec.spi.apikey

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

object ApiKeys {
    private const val KEY_BYTES = 32
    private const val PREFIX = "cosec_"
    private val random = SecureRandom()

    /** Lower-case hex, as the hashes of stored keys are written; a `String.format` per byte cost every request. */
    private val HEX: HexFormat = HexFormat.of()

    /** The SHA-256 of [key], hex-encoded: what a store keeps instead of the key. */
    fun hash(key: String): String = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))

    /** A new random key. Show it once and store only its [hash]. */
    fun generate(): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
