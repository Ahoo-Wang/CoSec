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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * Document keys as stores and codecs write them (`platform`, `app:<id>`, `tenant:<id>`), for what reads them back: one
 * way to put a stored key or id in a message.
 */
@InternalCoSecApi
object SnapshotKeyText {
    /**
     * [text] for a message or a log line: a control character as its `\uXXXX` escape, so none can forge a line (RC1-15),
     * and an unpaired surrogate too, which a UTF-8 log would write as `?`, naming another id (RC1-52).
     */
    fun printable(text: String): String = Identifiers.printable(text)

    /** The most characters of one stored value a message quotes. */
    const val MAX_QUOTED = 64

    /** The most names a message lists. */
    const val MAX_NAMES = 3

    /**
     * [text], a stored value quoted in a message a host logs: [printable], and at most [MAX_QUOTED] characters, never cut
     * inside a surrogate pair, saying how long it was. A value that does not decode is a log line's, which it neither
     * forges nor makes long (#1691's rule for the identity header, here for stored values).
     */
    fun quoted(text: String): String = bounded(text, MAX_QUOTED)

    /** The most characters of a whole message a codec passes on from a nested one. */
    const val MAX_MESSAGE = 512

    /**
     * [message], another codec's message passed on where a stored value's error is logged: [printable] and at most
     * [MAX_MESSAGE] characters, as [quoted] does for one value. That codec may quote what it read as it found it.
     */
    fun message(message: String): String = bounded(message, MAX_MESSAGE)

    private fun bounded(text: String, max: Int): String {
        if (text.length <= max) {
            return printable(text)
        }
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return "${printable(text.substring(0, end))}… (${text.length} characters)"
    }

    /** [names], each [quoted], at most [MAX_NAMES] of them and how many more. */
    fun names(names: Collection<String>): String {
        val listed = names.take(MAX_NAMES).joinToString(", ", "[", "]") { quoted(it) }
        return if (names.size > MAX_NAMES) "$listed and ${names.size - MAX_NAMES} more" else listed
    }
}
