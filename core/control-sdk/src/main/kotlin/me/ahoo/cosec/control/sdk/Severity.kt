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

/** How grave a finding of a change's check is (`severity`). A value this version does not know keeps its text ([ClosedValue]). */
@ExperimentalCoSecApi
class Severity private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        val ERROR: Severity = Severity("ERROR", false)
        val WARNING: Severity = Severity("WARNING", false)

        private val SET = ClosedSet(listOf(ERROR, WARNING)) { Severity(it, true) }

        /** Every value this version knows. */
        val KNOWN: List<Severity> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): Severity = SET.of(text)
    }
}
