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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Compile on sight (`CTL-12`): each window of the staged list is read and compiled once, when this instance first sees
 * it, instead of at the first request it samples of that document. One it cannot compile is recorded at once as
 * [ShadowOutcome.CANDIDATE_ERROR], which closes the window, whatever this instance's traffic.
 *
 * It runs on the caller's coroutine, the reporter's, one candidate at a time, through [source]'s compiler: never on the
 * snapshot source's compile pool or its queue, so a burst of windows staged while documents load cold makes no load fail
 * and no hand-off be refused. A window whose candidate is absent (its member was listed first) or cannot be read is
 * tried again on the next cycle; nothing it does reaches a request. Without a list read, it does nothing.
 */
internal class CompileOnSight(private val source: CachingCandidateSource, private val recorder: ShadowRecorder) {
    /** The windows compiled, or recorded as failing, by document: kept to what the list names. */
    private val seen: MutableSet<Pair<SnapshotKey, String>> = ConcurrentHashMap.newKeySet()

    suspend fun run() {
        val listed = source.staged.windows ?: return
        seen.retainAll { (key, window) -> listed[key]?.contains(window) == true }
        for ((key, windows) in listed) {
            for (window in windows) {
                if (key to window !in seen) {
                    sight(key, window)
                }
            }
        }
    }

    private suspend fun sight(key: SnapshotKey, window: String) {
        val candidate = source.sighted(key, window) ?: return
        // The candidate read is of the window it names: a member of another one is tried again next cycle.
        if (candidate.window != window || !seen.add(key to window)) {
            return
        }
        if (candidate is Candidate.Failed) {
            recorder.record(key, window, ShadowOutcome.CANDIDATE_ERROR, null, null)
        }
    }
}
