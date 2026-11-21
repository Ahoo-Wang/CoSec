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
 * The state of a change, as the control API writes it (`contract/control/vocabularies.json`): a closed set that a later
 * host may add to. A value this version does not know is still a [ChangeState], [isUnknown], with its [text] readable,
 * never a failure to read the answer (the reader's rule, `CTL-53`).
 */
@ExperimentalCoSecApi
class ChangeState private constructor(
    /** The value as the wire writes it: `READY`, `PUBLISHED`, ... */
    val text: String,
    /** Whether this version does not know the value. */
    val isUnknown: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ChangeState && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

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

        /** Every state this version knows, in the order of the control plane's lifecycle. */
        val KNOWN: List<ChangeState> =
            listOf(DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED, PUBLISHED, REJECTED, ABANDONED, EXPIRED)

        private val BY_TEXT = KNOWN.associateBy { it.text }

        /** The state [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): ChangeState = BY_TEXT[text] ?: ChangeState(text, true)
    }
}
