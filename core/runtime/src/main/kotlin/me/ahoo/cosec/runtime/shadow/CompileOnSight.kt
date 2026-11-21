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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * Compile on sight (`CTL-12`): each window of the staged list is read and compiled once, when this instance first sees
 * it, instead of at the first request it samples of that document. One it cannot compile is recorded as
 * [ShadowOutcome.CANDIDATE_ERROR], which closes the window, whatever this instance's traffic.
 *
 * It runs on the caller's coroutine, the reporter's, one candidate at a time, through [source]'s compiler: never on the
 * snapshot source's compile pool or its queue, so a burst of windows staged while documents load cold makes no load fail
 * and no hand-off be refused. Nothing it does reaches a request. Without a list read, it does nothing.
 *
 * **A budget per cycle.** One [run] spends at most [budget] on it, checked between candidates (one in progress is never
 * cut short), so the reporter writes its reports and its status every cycle however many windows a bulk change staged
 * at once. The windows not reached wait for the next cycles, the longest waiting first; a window whose candidate is
 * absent (its member was listed first), of another window, or cannot be read goes back to the end of the queue, so none
 * holds up the others. A candidate is therefore compiled within a few cycles of its staging, not always the first.
 */
internal class CompileOnSight(
    private val source: CachingCandidateSource,
    private val recorder: ShadowRecorder,
    private val budget: Duration,
    private val nanos: () -> Long = System::nanoTime,
) {
    /** The windows compiled, or recorded as failing: kept to what the list names. */
    private val seen = HashSet<Pair<SnapshotKey, String>>()

    /** The windows listed and not yet seen, the longest waiting first. */
    private val waiting = LinkedHashSet<Pair<SnapshotKey, String>>()

    /** One cycle at a time (a test may flush while the loop runs): the sets are guarded by it. */
    private val mutex = Mutex()

    /** One cycle: what was listed and not yet seen, the longest waiting first, until [budget] is spent. */
    suspend fun run() = mutex.withLock {
        val listed = source.staged.windows ?: return@withLock
        val named = HashSet<Pair<SnapshotKey, String>>()
        listed.forEach { (key, windows) -> windows.forEach { named += key to it } }
        seen.retainAll(named)
        waiting.retainAll(named)
        named.filterNot { it in seen }.forEach(waiting::add)
        val deadline = nanos() + budget.toNanos()
        for (next in waiting.toList()) {
            if (nanos() - deadline >= 0) {
                break
            }
            waiting.remove(next)
            if (sight(next.first, next.second)) {
                seen += next
            } else {
                waiting += next
            }
        }
    }

    /**
     * The windows this instance acknowledges (`InstanceStatus.windows`, `CTL-10`): those of the list it holds whose
     * candidate it compiled, or recorded as failing. Null when it holds no list: it acknowledges nothing.
     */
    suspend fun acknowledged(): Set<String>? = mutex.withLock {
        if (source.staged.windows == null) null else seen.mapTo(HashSet()) { it.second }
    }

    /** Whether [window] was compiled, or recorded as failing; false to try it again on a later cycle. */
    private suspend fun sight(key: SnapshotKey, window: String): Boolean {
        val candidate = source.sighted(key, window) ?: return false
        // The candidate read is of the window it names: a member of another one is tried again later.
        if (candidate.window != window) {
            return false
        }
        if (candidate is Candidate.Failed) {
            recorder.record(key, window, ShadowOutcome.CANDIDATE_ERROR, null, null)
        }
        return true
    }
}
