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
 * What a check answers (`CTL-78`, `contract/control/change-check.json`): what the proposal it stands for would find,
 * without making it. The [state] it would be in ([ChangeState.CHECKED] when every check passes, [ChangeState.DRAFT]
 * with [findings] otherwise), the [base] it would be proposed on, and what it grants that would wait for a person
 * ([escalations]). No candidate digest: the document a proposal would publish names the change that makes it (who
 * confirmed a lapsing DENY, in which change), which a check does not make.
 */
@ExperimentalCoSecApi
class ChangeCheck(
    val state: ChangeState,
    val base: ChangeBase,
    findings: List<Finding>,
    escalations: List<Escalation>,
) {
    val findings: List<Finding> = findings.toList()

    val escalations: List<Escalation> = escalations.toList()

    override fun equals(other: Any?): Boolean = other is ChangeCheck && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "ChangeCheck",
        "state" to state,
        "base" to base,
        "findings" to findings,
        "escalations" to escalations,
    )

    private fun parts(): List<Any?> = listOf(state, base, findings, escalations)
}
