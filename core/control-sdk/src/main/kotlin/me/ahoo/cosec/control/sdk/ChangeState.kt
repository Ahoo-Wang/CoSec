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

/** The state of a change (`contract/control/vocabularies.json`, `state`). A value this version does not know keeps its text ([ClosedValue]). */
@ExperimentalCoSecApi
class ChangeState private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        val DRAFT: ChangeState = ChangeState("DRAFT", false)
        val CHECKED: ChangeState = ChangeState("CHECKED", false)
        val SHADOWING: ChangeState = ChangeState("SHADOWING", false)
        val AWAITING_APPROVAL: ChangeState = ChangeState("AWAITING_APPROVAL", false)
        val READY: ChangeState = ChangeState("READY", false)
        val CONFLICTED: ChangeState = ChangeState("CONFLICTED", false)
        val PUBLISHED: ChangeState = ChangeState("PUBLISHED", false)
        val REJECTED: ChangeState = ChangeState("REJECTED", false)
        val ABANDONED: ChangeState = ChangeState("ABANDONED", false)
        val EXPIRED: ChangeState = ChangeState("EXPIRED", false)

        private val SET =
            ClosedSet(
                listOf(DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED, PUBLISHED, REJECTED, ABANDONED, EXPIRED)
            ) { ChangeState(it, true) }

        /** Every value this version knows. */
        val KNOWN: List<ChangeState> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): ChangeState = SET.of(text)
    }
}
