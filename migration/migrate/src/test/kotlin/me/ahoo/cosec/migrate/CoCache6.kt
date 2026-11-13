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

/** Values as CoCache 6 wrote them in Redis (format 1), which is how 7.x data is stored: see [CoCache6Format]. */
internal object CoCache6 {
    private const val TOKEN_AND_TIME = 32

    /** A value under a header with no token: magic, format 1, `V`, a reserved byte, then zeros. */
    fun value(payload: ByteArray, state: Byte = CoCache6Format.STATE_VALUE): ByteArray =
        byteArrayOf(CoCache6Format.MAGIC, CoCache6Format.FORMAT, state, 0) + ByteArray(TOKEN_AND_TIME) + payload

    /** The fields of a set: `!f` = `1`, and `+member` = empty for each member. */
    fun set(vararg members: String): Map<ByteArray, ByteArray> =
        mapOf(CoCache6Format.SET_FORMAT_FIELD to "1".encodeToByteArray()) +
            members.associate { "+$it".encodeToByteArray() to ByteArray(0) }
}
