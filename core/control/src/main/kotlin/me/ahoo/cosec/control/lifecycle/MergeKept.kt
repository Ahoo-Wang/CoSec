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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocument

/**
 * UC-2: what an `apply` with `MERGE` keeps although the file leaves it out. `MERGE` only adds and changes, so an entry
 * removed from the file (a revoked grant) stays live; the answer says so, one `WARNING` per section, naming the ids and
 * `REPLACE`, which removes them. The removes of a `REPLACE` are its operations already.
 */
internal object MergeKept {
    fun findings(head: PolicyDocument?, desired: PolicyDocument): List<Finding> {
        head ?: return emptyList()
        return listOfNotNull(
            kept("policies", head.policies.map { it.id.value }, desired.policies.map { it.id.value }),
            kept("bindings", head.bindings.map { it.id.value }, desired.bindings.map { it.id.value }),
            kept("roles", head.roles.map { it.id.value }, desired.roles.map { it.id.value }),
            kept("rateLimits", head.rateLimits.map { it.id }, desired.rateLimits.map { it.id }),
        )
    }

    private fun kept(section: String, head: List<String>, desired: List<String>): Finding? {
        val kept = head - desired.toSet()
        if (kept.isEmpty()) {
            return null
        }
        val (verb, stays) = if (kept.size == 1) "is" to "stays" else "are" to "stay"
        val message = "${kept.size} of the head's $section $verb not in the file and $stays: " +
            "${kept.joinToString(", ", "[", "]")}. MERGE keeps what the file leaves out; apply with REPLACE to remove them"
        return Finding("OPERATION", "$.$section", message, UNPROCESSABLE, Severity.WARNING)
    }
}
