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

package me.ahoo.cosec.runtime.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotKey

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/** What a [SnapshotSource] does when the store says documents changed (RFC 0013 B2a): reload the slot of each. */
internal class SnapshotChanges(
    private val sharedKey: SnapshotKey,
    private val shared: Slot<*>,
    private val families: SnapshotFamilies<*>,
    private val passes: Passes,
) : SnapshotChangeListener {
    override fun onChanged(keys: Collection<SnapshotKey>) {
        for (key in keys) {
            if (key == sharedKey) {
                shared.onChanged()
                continue
            }
            // A key of no family here is another source's document.
            val family = families.firstOrNull { it.spec.kind == key.kind } ?: continue
            family.slots[family.spec.idOf(key)]?.onChanged()
        }
    }

    override fun onReset() {
        log.info { "Snapshot change notifications may have been lost; reloading every installed document." }
        passes.reloadAll(passes.installedSlots()) { it.reload(force = false) }
    }
}
