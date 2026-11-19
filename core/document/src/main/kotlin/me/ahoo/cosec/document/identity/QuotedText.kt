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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.model.Identifiers

/**
 * Text of an identity header quoted in a refusal's message, which a service in gateway mode logs at `WARN` (`OBS-7`)
 * and which a caller that did not come through the gateway chooses: a control character or an unpaired surrogate as
 * its `\uXXXX` escape, as CoSec writes ids in log lines (RC1-15, RC1-52), and at most [MAX_LENGTH] characters, so a
 * forged header can neither write a line of its own nor make a long one. Built only when a message is.
 */
internal object QuotedText {
    /** The most characters of one name or value a message quotes. */
    const val MAX_LENGTH = 64

    /** The most names a message lists. */
    const val MAX_NAMES = 3

    fun of(text: String): String {
        if (text.length <= MAX_LENGTH) return Identifiers.printable(text)
        return Identifiers.printable(text.substring(0, MAX_LENGTH)) + "… (${text.length} characters)"
    }

    fun names(names: List<String>): String {
        val listed = names.take(MAX_NAMES).joinToString(", ", "[", "]") { of(it) }
        return if (names.size > MAX_NAMES) "$listed and ${names.size - MAX_NAMES} more" else listed
    }
}
