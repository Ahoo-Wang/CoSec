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

@file:OptIn(me.ahoo.cosec.annotations.ExperimentalCoSecApi::class, me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.cocache.control

import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException

/** Every open change, through the state sets page by page, as the tests read them; one that does not read throws. */
suspend fun ControlStore.allOpenChanges(): List<ChangeRequest> = ChangeState.entries.filterNot {
    it.terminal
}.flatMap { state ->
    val found = ArrayList<ListedChange>()
    var after: String? = null
    do {
        val page = changes(state, after, 100)
        found += page.changes
        after = page.next
    } while (after != null)
    found
}.map { listed ->
    when (listed) {
        is ListedChange.Readable -> listed.change
        is ListedChange.Unreadable -> throw SnapshotFormatException("Change [${listed.id.value}] does not read.")
    }
}
