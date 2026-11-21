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

/** What a change is (`kind`): an ordinary publish, a rollback, break-glass, an expiry, a migration, a bootstrap. A value this version does not know keeps its text ([ClosedValue]). */
@ExperimentalCoSecApi
class ChangeKind private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        val PUBLISH: ChangeKind = ChangeKind("PUBLISH", false)
        val ROLLBACK: ChangeKind = ChangeKind("ROLLBACK", false)
        val BREAK_GLASS: ChangeKind = ChangeKind("BREAK_GLASS", false)
        val EXPIRE: ChangeKind = ChangeKind("EXPIRE", false)
        val MIGRATE: ChangeKind = ChangeKind("MIGRATE", false)
        val BOOTSTRAP: ChangeKind = ChangeKind("BOOTSTRAP", false)

        private val SET = ClosedSet(listOf(PUBLISH, ROLLBACK, BREAK_GLASS, EXPIRE, MIGRATE, BOOTSTRAP)) { ChangeKind(it, true) }

        /** Every value this version knows. */
        val KNOWN: List<ChangeKind> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): ChangeKind = SET.of(text)
    }
}
