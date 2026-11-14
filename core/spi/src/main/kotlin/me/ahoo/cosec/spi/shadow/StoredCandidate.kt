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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDocument

/**
 * A staged change's candidate for one document (RFC 0007 §6.4), as the control plane writes it to `candidate:<docKey>`:
 * the shadow window it belongs to, the revision of the head it was made on, and the document it would publish.
 */
@ExperimentalCoSecApi
class StoredCandidate(val window: String, val baseRevision: Long, val document: SnapshotDocument) {
    init {
        require(
            window.isNotEmpty() && baseRevision >= 0
        ) { "A candidate names its window and the revision of its base." }
    }
}
