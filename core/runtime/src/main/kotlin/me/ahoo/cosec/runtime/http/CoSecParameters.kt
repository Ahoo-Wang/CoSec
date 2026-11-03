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

/**
 * The `CoSec-*` request parameters a client may send as a header or as a query parameter. Empty means not sent.
 *
 * `SCP-4` (RFC 0008 §3.2): a parameter is sent once, or several times with one value. When its values differ, whether
 * between the header and the query or within either, the request cannot be interpreted without ambiguity and is
 * rejected with 400: the pipeline and the backend could otherwise read different scopes. A blank value next to a
 * non-blank one differs too (RC1-16): a backend that reads the blank one would be in the default space. Only values
 * that are all blank read as not sent, and blank means ASCII whitespace only: a value of Unicode spaces is a value,
 * which a backend's `trim()` keeps.
 */
class CoSecParameters internal constructor(val appId: String, val spaceId: String, val deviceId: String) {
    companion object {
        const val APP_ID = "CoSec-App-Id"
        const val SPACE_ID = "CoSec-Space-Id"
        const val DEVICE_ID = "CoSec-Device-Id"

        /** Header only: a response echoes it, and it identifies the request in audit (RFC 0006 §6.2). */
        const val REQUEST_ID = "CoSec-Request-Id"

        private val NAMES = setOf(APP_ID, SPACE_ID, DEVICE_ID)

        /**
         * The parameters of [facts], or null when one of them is ambiguous. Query names match exactly, header names
         * ignoring case. A query value of a `CoSec-*` parameter with a malformed percent-escape is ambiguous too;
         * other query parameters are not read.
         */
        fun read(facts: HttpFacts): CoSecParameters? {
            val query = QueryParameters.values(facts.rawQuery, NAMES) ?: return null
            val appId = single(facts.headers.values(APP_ID), query[APP_ID]) ?: return null
            val spaceId = single(facts.headers.values(SPACE_ID), query[SPACE_ID]) ?: return null
            val deviceId = single(facts.headers.values(DEVICE_ID), query[DEVICE_ID]) ?: return null
            return CoSecParameters(appId, spaceId, deviceId)
        }

        /**
         * The one non-blank value, empty when every value is blank or there is none, null when two differ: two
         * non-blank ones, or a blank one beside a non-blank one. Indexed, so it allocates nothing.
         */
        private fun single(headers: List<String>, query: List<String>?): String? {
            val value = firstNonBlank(headers) ?: query?.let(::firstNonBlank) ?: return ""
            return if (allAre(value, headers) && (query == null || allAre(value, query))) value else null
        }

        private fun firstNonBlank(values: List<String>): String? {
            for (index in values.indices) {
                if (!isBlank(values[index])) {
                    return values[index]
                }
            }
            return null
        }

        private fun allAre(value: String, values: List<String>): Boolean {
            for (index in values.indices) {
                if (values[index] != value) {
                    return false
                }
            }
            return true
        }

        /** Empty, or only ASCII whitespace (tab, line feed, form feed, carriage return, space), never Unicode spaces. */
        internal fun isBlank(value: String): Boolean {
            for (index in value.indices) {
                when (value[index]) {
                    ' ', '\t', '\n', '\u000C', '\r' -> continue
                    else -> return false
                }
            }
            return true
        }
    }
}
