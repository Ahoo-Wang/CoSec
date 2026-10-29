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

package me.ahoo.cosec.runtime

/** Which client request ids the pipeline accepts. */
internal object RequestIds {
    /** The longest id accepted; a UUID is 36 characters. */
    const val MAX_LENGTH = 64

    /**
     * Whether [id] is a plain id: 1 to [MAX_LENGTH] characters of `A-Z a-z 0-9 . _ -`. Anything else, with spaces,
     * quotes, control characters or non-ASCII, is replaced.
     */
    fun isAcceptable(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_LENGTH) {
            return false
        }
        for (c in id) {
            val plain = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'
            if (!plain) {
                return false
            }
        }
        return true
    }
}
