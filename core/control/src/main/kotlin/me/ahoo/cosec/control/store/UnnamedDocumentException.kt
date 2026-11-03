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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.model.CoSecException
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException

/**
 * A stored change request whose own document is an app no document key can name: an id the app id rule refuses
 * (`SnapshotKey.App`, SCP-7), which builds before R2-1 of #1194's review accepted. [ChangeRequestCodec] throws it, so a
 * [ControlStore] that decodes with it does too: a format error of that record alone, never a [SnapshotFormatException].
 * Such a change can no longer be read, shown or abandoned. A store keeps it listed in [ControlStore.openChanges], since
 * it cannot tell whether it is terminal; maintenance skips it with a WARN, and the control API answers it `409`. An
 * operator removes its record. Reclamation loses nothing by it: the only revisions such a change rests on are of a
 * document no key can name, which reclamation never visits.
 *
 * [key] is the stored key as written, escaped (`\uXXXX` for a control character), so it can be logged. Only CoSec's
 * modules construct it.
 */
class UnnamedDocumentException @InternalCoSecApi constructor(val key: String) : CoSecException(
    "A stored change request is of [$key], which no document key can name (SCP-7): the record is skipped."
) {
    companion object {
        /** The [UnnamedDocumentException] [error] is or was caused by, as a store may wrap what its codec threw. */
        @InternalCoSecApi
        fun of(error: Throwable): UnnamedDocumentException? =
            generateSequence(error) { it.cause }.filterIsInstance<UnnamedDocumentException>().firstOrNull()
    }
}
