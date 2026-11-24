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
 * Why what a change grants waits for a person (`escalations`, RFC 0007 §7.4): [subject], for [reason]. An entry of the
 * grant analysis stands for the fragments of one statement: its [subject] names how many and their digest, and its
 * [detail] the first three. A change answers at most about 200 of them (`CTL-88`, `contract/control/escalations.json`),
 * so an approval screen can show them all.
 */
@ExperimentalCoSecApi
class Escalation(val subject: String, val reason: EscalationReason, val detail: String) {
    override fun equals(other: Any?): Boolean =
        other is Escalation && subject == other.subject && reason == other.reason && detail == other.detail

    override fun hashCode(): Int = listOf(subject, reason, detail).hashCode()

    override fun toString(): String =
        describe("Escalation", "subject" to subject, "reason" to reason, "detail" to detail)
}
