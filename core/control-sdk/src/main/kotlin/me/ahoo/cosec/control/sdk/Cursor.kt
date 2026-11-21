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
 * Where the next page of a listing starts (`contract/control/cursor.json`): the `next` of the page before, passed back
 * unchanged as `after`. Its text holds nothing a client may rely on, holds no server state and does not expire.
 */
@ExperimentalCoSecApi
class Cursor private constructor(val text: String) {
    override fun equals(other: Any?): Boolean = other is Cursor && text == other.text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    @ExperimentalCoSecApi
    companion object {
        private val ALPHABET = Regex("[A-Za-z0-9_-]+")

        /** The cursor [text] is; [IllegalArgumentException] when it is not of the cursor's alphabet. */
        fun of(text: String): Cursor {
            require(ALPHABET.matches(text)) { "A cursor is the next of a page, passed back unchanged." }
            return Cursor(text)
        }
    }
}
