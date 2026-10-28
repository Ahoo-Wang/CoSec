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

/** Reads query parameters from a raw query string, as the hosts do: `&`-separated, decoded as forms (`+` is a space). */
internal object QueryParameters {
    /**
     * The values of the parameters named in [names], in order; names match exactly after decoding. Null when a value
     * of one of them has a malformed percent-escape. A pair whose name does not decode is not one of them.
     */
    fun values(rawQuery: String?, names: Set<String>): Map<String, List<String>>? {
        // An encoded name, such as `%43oSec-App-Id`, decodes to one of the names too.
        if (rawQuery.isNullOrEmpty() || ('%' !in rawQuery && names.none { it in rawQuery })) {
            return emptyMap()
        }
        val values = HashMap<String, MutableList<String>>()
        for (pair in rawQuery.split('&')) {
            val name = PercentDecoder.decode(pair.substringBefore('='), plusIsSpace = true)
            if (name == null || name !in names) {
                continue
            }
            val raw = if ('=' in pair) pair.substringAfter('=') else ""
            val value = PercentDecoder.decode(raw, plusIsSpace = true) ?: return null
            values.getOrPut(name) { ArrayList(1) } += value
        }
        return values
    }
}
