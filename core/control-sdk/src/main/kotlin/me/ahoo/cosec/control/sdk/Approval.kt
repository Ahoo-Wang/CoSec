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
import java.time.Instant

/**
 * Who approved a change, when, which [candidate] the approval is of, which escalations it [covers], and whose the
 * approver's credential was ([kind], `APR-9`): null for an approval recorded before kinds, which does not count at
 * publication.
 */
@ExperimentalCoSecApi
class Approval(
    val approver: Actor,
    val at: Instant,
    val candidate: String,
    covers: List<String>,
    val kind: ApproverKind? = null,
) {
    val covers: List<String> = covers.toList()

    override fun equals(other: Any?): Boolean = other is Approval && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Approval",
        "approver" to approver,
        "at" to at,
        "candidate" to candidate,
        "covers" to covers,
        "kind" to kind,
    )

    private fun parts(): List<Any?> = listOf(approver, at, candidate, covers, kind)
}
