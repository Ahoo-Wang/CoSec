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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration
import java.time.Instant

/**
 * The sessions of each principal in each tenant, in the order they began, for a [TokenFamilyStore] to keep at most
 * [TokenFamilyStore.SESSIONS_PER_PRINCIPAL] of. CoSec's stores share it: [InMemorySessionIndex] in one process, an index
 * on Redis for `cosec-cocache`. A store admits a family here before it writes the family's record, so a record never
 * exists that the index does not count; an index entry whose record was never written takes a place until it is pushed
 * out, and deleting its record then does nothing.
 */
@InternalCoSecApi
fun interface SessionIndex {
    /**
     * Counts [family] as its principal's latest session in its tenant, begun [now], and answers the ids of the sessions
     * that pushes out, oldest first: past [limit], the earliest begun. The index's own entry for the principal is kept
     * for at least [keepFor], the longest any of its sessions can still live.
     */
    suspend fun admit(family: TokenFamily, now: Instant, keepFor: Duration, limit: Int): List<String>
}
