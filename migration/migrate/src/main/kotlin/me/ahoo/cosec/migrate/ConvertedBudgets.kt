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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentBudgets
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * `CTL-40`: a converted document over one of the default entry budgets is still written, and hosts serve it; the
 * control plane only refuses a change that adds to a count over its budget. So it is a warning naming the budget, the
 * count and the limit: the first change to that document must not grow it.
 */
internal object ConvertedBudgets {
    fun warnings(key: SnapshotKey, document: PolicyDocument): List<Warning> =
        DocumentBudgets.Entry.entries.mapNotNull { entry ->
            val count = entry.count(document)
            val limit = entry.limit(DocumentBudgets.DEFAULT)
            if (count <= limit) {
                null
            } else {
                Warning(
                    "$key#${entry.path}",
                    "the $key document holds $count ${entry.what}, more than the default limits.${entry.member} " +
                        "($limit, CTL-40): it is written and served, but the control plane refuses any change that " +
                        "adds to them; raise limits.${entry.member} in the platform document, or make the first " +
                        "change shrink it",
                )
            }
        }
}
