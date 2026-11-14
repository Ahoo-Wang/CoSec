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

package me.ahoo.cosec.runtime.http

/** How the pipeline reads a request's method (`PIP-18`). */
internal object HttpMethods {
    const val GET = "GET"
    const val HEAD = "HEAD"

    /**
     * Whether [method] holds an ASCII lower-case letter: refused with 400. Methods are case-sensitive (RFC 9110 §9.1) and
     * every registered one is upper case, but some backends fold case, so `delete` would be served as DELETE while a
     * DENY on `http:DELETE` does not name it.
     */
    fun hasLowerCase(method: String): Boolean {
        for (char in method) {
            if (char in 'a'..'z') return true
        }
        return false
    }

    /** The method [method] is decided as: GET for HEAD, which servers serve with the GET handler, else [method]. */
    fun decidedAs(method: String): String = if (method == HEAD) GET else method
}
