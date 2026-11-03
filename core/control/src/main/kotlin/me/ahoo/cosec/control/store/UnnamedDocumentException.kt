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

import me.ahoo.cosec.model.CoSecException

/**
 * A stored change request whose own document is an app no document key can name: an id the app id rule refuses
 * (`SnapshotKey.App`, SCP-7), which builds before R2-1 of #1194's review accepted. A format error of that record alone:
 * maintenance and listing skip it with a WARN, and reclamation loses nothing by it, since the only revisions such a
 * change rests on are of a document no key can name, which reclamation never visits. [key] is the stored key as
 * written, escaped (`\uXXXX` for a control character), so it can be logged.
 */
class UnnamedDocumentException(val key: String) : CoSecException(
    "A stored change request is of [$key], which no document key can name (SCP-7): the record is skipped."
) {
    companion object {
        /** The [UnnamedDocumentException] [error] is or was caused by, as a store may wrap what its codec threw. */
        fun of(error: Throwable): UnnamedDocumentException? =
            generateSequence(error) { it.cause }.filterIsInstance<UnnamedDocumentException>().firstOrNull()
    }
}
