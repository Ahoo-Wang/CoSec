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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The candidates staged now, as the control plane lists them (`CTL-46`): each document with the windows it stages. The
 * list is a superset of what is staged: a document is listed before its candidate is written and unlisted after it is
 * withdrawn, so a listed document may have no candidate any more, and an unlisted one never has one.
 */
@InternalCoSecApi
class StagedCandidates(val windows: Map<SnapshotKey, Set<String>>) {
    /** Whether [key] is listed: it may stage a candidate, which is read by its key. */
    fun isStaged(key: SnapshotKey): Boolean = key in windows

    override fun equals(other: Any?): Boolean = other is StagedCandidates && other.windows == windows

    override fun hashCode(): Int = windows.hashCode()

    override fun toString(): String = "StagedCandidates($windows)"

    @InternalCoSecApi
    companion object {
        /** A list that is kept and names nothing: nothing is staged. */
        val NONE: StagedCandidates = StagedCandidates(emptyMap())
    }
}
