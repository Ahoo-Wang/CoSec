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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * What [ReferenceRepair.run] did: the documents it visited, the references it added and removed, the documents it could
 * not, and how many apps the app list names now, `null` when it could not be completed. [converted] back-reference
 * values went from the format before 8.0.0-beta.5 to sets; [converting] says the store had the old format at the start,
 * [formatDone] that it has it no more. [busy]: another run held the lease, and this one did nothing; [holder] names it.
 */
class RepairResult(
    val documents: Int,
    val added: Int,
    val removed: Int,
    val failed: Map<SnapshotKey, String>,
    val apps: Int?,
    val converted: Int = 0,
    val converting: Boolean = false,
    val formatDone: Boolean = false,
    val busy: Boolean = false,
    /** Who holds the lease when [busy]: the other run's id and when it started, if the store can say. */
    val holder: String? = null,
    /** The open changes the old list named that were listed in the sets (`CTL-54`). */
    val openChanges: Int = 0,
    /** The ids of listed changes whose record could not be read: until none is left, the sets are not kept. */
    val unreadOpenChanges: List<String> = emptyList(),
)
