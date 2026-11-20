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

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cocache.api.SetStore
import me.ahoo.cosec.cocache.control.ControlCacheSpecs
import me.ahoo.cosec.cocache.control.ControlSets
import me.ahoo.cosec.cocache.control.StagedMember
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The data plane's [CandidateStore] on Redis: reads `<p>:candidate:<docKey>` from Redis, never a local copy
 * (`lookupRemote`, CoCache 8.0.0-rc.1), and throws when Redis fails. With [staged], it reads the staged list
 * `<p>:candidate-staged:all` (`CTL-46`, `CTL-47`) from Redis too, every read: null without the control plane's marker,
 * and a failure throws, never an empty list.
 */
@InternalCoSecApi
class CoCacheCandidateStore(
    private val candidates: Store<SnapshotKey, StoredCandidate>,
    private val staged: SetStore<String, String>? = null,
) : CandidateStore {
    override suspend fun candidate(key: SnapshotKey): StoredCandidate? =
        when (val read = candidates.suspending().lookupRemote(key)) {
            is Lookup.Present -> read.value
            is Lookup.Absent -> null
        }

    override suspend fun staged(): StagedCandidates? {
        val set = staged ?: return null
        return StagedMember.list(set.suspending().get(ControlSets.STAGED_SET))
    }

    @InternalCoSecApi
    companion object {
        /** A reader of the candidates; [readOnly] where this process does not run the control plane. */
        fun create(
            factory: CoCacheFactory,
            keyPrefix: String = DEFAULT_KEY_PREFIX,
            readOnly: Boolean = false,
        ): CoCacheCandidateStore = CoCacheCandidateStore(
            factory.createLocked(ShadowCacheSpecs.candidate(keyPrefix, readOnly)),
            factory.createLocked(ControlCacheSpecs.stagedSet(keyPrefix, readOnly)),
        )
    }
}
