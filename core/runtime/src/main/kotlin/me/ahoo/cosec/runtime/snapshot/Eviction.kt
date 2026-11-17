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

package me.ahoo.cosec.runtime.snapshot

/** What an eviction of one key came to (`SNP-14`). */
internal enum class Eviction {
    /** The key left its family. */
    REMOVED,

    /** Loading, already removed, holding a document when only an empty key was to go, or of the other kind by now. */
    REFUSED,

    /** The family's claim failed: the kind is at its bound, and a pass stops offering. */
    AT_BOUND,
}
