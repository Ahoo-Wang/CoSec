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

/**
 * CoCache 6's Redis format 1, which CoSec 7.x data is stored in and CoCache 8 does not read:
 *
 * - a scalar is a string: a 36-byte header, then the payload (the codec's bytes). The header is `0xC0` (magic), the
 *   format (`1`), the state (`V` a value, `M` a cached absence, `L` a load lease), a reserved byte, a 24-byte token and
 *   its 8-byte issue time;
 * - a set is a hash: the field `!f` holds the format (`1`), and each member is a field `+` followed by its bytes, whose
 *   value is empty.
 *
 * Anything else under a key 7.x wrote is content this tool cannot read: it fails the read, naming the key, rather than
 * being taken for an absent value.
 */
internal object CoCache6Format {
    const val FORMAT: Byte = 1
    const val HEADER_SIZE = 36
    const val MAGIC: Byte = 0xC0.toByte()
    const val STATE_VALUE = 'V'.code.toByte()
    const val STATE_MISSING = 'M'.code.toByte()
    const val STATE_LEASE = 'L'.code.toByte()
    const val MEMBER_PREFIX = '+'.code.toByte()
    val SET_FORMAT_FIELD: ByteArray = "!f".encodeToByteArray()

    private const val NONE = "none"
    private const val STRING = "string"
    private const val HASH = "hash"

    /**
     * The payload stored under [storageKey], of Redis type [type], or null when it holds no value (no key, a cached
     * absence, a load lease); [read] reads the string.
     */
    fun scalar(storageKey: String, type: String, read: () -> ByteArray?): ByteArray? {
        when (type) {
            NONE -> return null
            STRING -> Unit
            else -> unreadable(storageKey, "a $type, not a CoCache 6 value")
        }
        val raw = read() ?: return null
        if (raw.size < HEADER_SIZE || raw[0] != MAGIC) unreadable(storageKey, "not written by CoCache 6")
        if (raw[1] != FORMAT) unreadable(storageKey, "CoCache format ${raw[1]}, not $FORMAT")
        return when (raw[2]) {
            STATE_VALUE -> raw.copyOfRange(HEADER_SIZE, raw.size)
            STATE_MISSING, STATE_LEASE -> null
            else -> unreadable(storageKey, "an unknown CoCache 6 state ${raw[2]}")
        }
    }

    /**
     * The members of the set under [storageKey], of Redis type [type]; empty when there is no key. [read] reads the hash's
     * fields and its `!f` field.
     */
    fun members(storageKey: String, type: String, read: () -> Pair<List<ByteArray>, ByteArray?>): List<ByteArray> {
        when (type) {
            NONE -> return emptyList()
            HASH -> Unit
            else -> unreadable(storageKey, "a $type, not a CoCache 6 set")
        }
        val (fields, format) = read()
        if (format?.decodeToString() != FORMAT.toString()) {
            unreadable(storageKey, "a set of CoCache format ${format?.decodeToString()}, not $FORMAT")
        }
        return fields.filter { it.isNotEmpty() && it[0] == MEMBER_PREFIX }.map { it.copyOfRange(1, it.size) }
    }

    private fun unreadable(storageKey: String, reason: String): Nothing =
        throw IllegalStateException("[$storageKey] holds content this tool cannot read: $reason.")
}
