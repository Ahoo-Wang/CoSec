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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * A value of one of the control API's closed sets (`contract/control/vocabularies.json`, the document format's enums), as
 * the sdk writes every one of them: a constant per value this version knows, and `of(text)` for a value read from the
 * wire, which a later host may add to. A value this version does not know is still read, [isUnknown], with its [text]
 * kept, never a failure to read the answer (the reader's rule, `CTL-53`). Two values are equal when they are of the same
 * set and have the same text.
 */
@ExperimentalCoSecApi
abstract class ClosedValue protected constructor(
    /** The value as the wire writes it. */
    val text: String,
    /** Whether this version does not know the value. */
    val isUnknown: Boolean,
) {
    final override fun equals(other: Any?): Boolean =
        other is ClosedValue && other::class == this::class && other.text == text

    final override fun hashCode(): Int = text.hashCode()

    final override fun toString(): String = text
}

/** The constants of one closed set, by their text, and how an unknown value of it is made. */
internal class ClosedSet<T : ClosedValue>(val known: List<T>, private val unknown: (String) -> T) {
    private val byText = known.associateBy { it.text }

    fun of(text: String): T = byText[text] ?: unknown(text)
}
