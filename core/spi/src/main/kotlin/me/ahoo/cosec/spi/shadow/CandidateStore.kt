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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Where candidates are read from: the `candidate:` namespace. Reads never block the caller.
 *
 * The shadow window's ports and values (this one, `ShadowReportSink`, `ShadowReportReader`) are not an extension point
 * in 8.0 (`@InternalCoSecApi`): `cosec-cocache` implements them all, the starter wires them, and they follow the control
 * plane's protocol, which changes in any release.
 */
@InternalCoSecApi
fun interface CandidateStore {
    suspend fun candidate(key: SnapshotKey): StoredCandidate?

    /**
     * The staged list (`CTL-46`, `CTL-47`): the documents that may stage a candidate, or null when no list is kept, as
     * by a store or a control plane that does not keep one. Null means: read every candidate by its key, as without a
     * list. A store that keeps one throws when it cannot be read, never answering an empty list for it.
     */
    suspend fun staged(): StagedCandidates? = null
}
