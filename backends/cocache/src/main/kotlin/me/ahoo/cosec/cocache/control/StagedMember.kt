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

package me.ahoo.cosec.cocache.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * A member of the staged list (`CTL-46`): `<docKey>/<window>`, the document's key as the candidates are stored under
 * ([SnapshotKeyEncoder]) and the window its candidate is staged for. No document key holds a `/` (an app or tenant id
 * never does), so the first one splits it, whatever the window id holds; a window id holds an `@` already, which is why
 * it is not the separator. [KEPT], which no document key can be, says the list is kept.
 */
internal object StagedMember {
    /** The marker of a kept list: present from the control plane's first start on, and never removed by it. */
    const val KEPT = ".kept"

    private const val SEPARATOR = '/'

    fun encode(key: SnapshotKey, window: String): String {
        require(window.isNotEmpty()) { "A staged candidate names its window." }
        return SnapshotKeyEncoder.encode(key) + SEPARATOR + window
    }

    /** The document and window [member] names, or null for the marker and for a member that names no document. */
    fun decode(member: String): Pair<SnapshotKey, String>? {
        val split = member.indexOf(SEPARATOR)
        if (split <= 0 || split == member.length - 1) {
            return null
        }
        val key = SnapshotKeyEncoder.decode(member.substring(0, split)) ?: return null
        return key to member.substring(split + 1)
    }
}
