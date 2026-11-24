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

package me.ahoo.cosec.conformance

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.store.ControlStore

/** What a change scenario ends with: each named change's findings, and the heads. */
internal object ChangeOutcome {
    /**
     * The errors, escalations and warnings of the changes [named] that are still stored: the bound on a document's ended
     * changes (`CTL-83`) removes the oldest, and a removed change has none.
     */
    suspend fun of(
        changes: Changes,
        store: ControlStore,
        named: Collection<String>,
        steps: List<String>,
        heads: Map<String, Long>,
    ): Outcome.Changed {
        val present = named.filter { store.change(ChangeId(it)) != null }.associateWith { changes.get(ChangeId(it)) }
        val errors = present.mapValues { (_, change) -> change.errors.map { it.check } }
        val escalations = present.mapValues { (_, change) -> change.escalations.map { it.reason.name } }
            .filterValues { it.isNotEmpty() }
        val warnings = present.mapValues { (_, change) ->
            change.findings.filter { it.severity == Severity.WARNING }.map { "${it.check} ${it.path}" }
        }.filterValues { it.isNotEmpty() }
        return Outcome.Changed(steps, errors, heads, escalations, warnings)
    }
}
