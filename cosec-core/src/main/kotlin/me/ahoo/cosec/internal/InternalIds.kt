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
package me.ahoo.cosec.internal

/**
 * Internal Id Tool .
 *
 * @author ahoo wang
 */
object InternalIds {
    /**
     * 为了避免排序问题选择 `(` 作为前缀.
     * `'('<'0'`
     */
    private const val PREFIX = "("

    /**
     * 为了避免排序问题选择 `)` 作为后缀.
     * `')'<'0'`
     */
    private const val SUFFIX = ")"

    @JvmStatic
    fun wrap(raw: String): String {
        return "$PREFIX$raw$SUFFIX"
    }

    @JvmStatic
    fun unwrap(wrapped: String): String {
        require(isWrapped(wrapped)) { "wrapped:[$wrapped] is not internal." }
        return wrapped.substring(PREFIX.length, wrapped.length - 1)
    }

    @JvmStatic
    fun isWrapped(wrapped: String): Boolean {
        return (wrapped.length > 2 && wrapped.startsWith(PREFIX) && wrapped.endsWith(SUFFIX))
    }
}
