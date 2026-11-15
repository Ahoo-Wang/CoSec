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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * [withTimeoutOrNull] that arms its timer only once [block] really suspends.
 *
 * [withTimeoutOrNull] arms a timer on kotlinx's `DefaultExecutor` before it starts the block and unparks that thread,
 * then cancels the timer when the block returns: a fixed cost on every request, larger than the decision itself on the
 * hot path (RFC 0008 §3.8; the end-to-end slice measured it). Here [block] starts undispatched, on the caller's thread
 * and without a timer. When it completes without suspending, as a request served from installed snapshots and local
 * caches does (RFC 0006 T1), no timer is ever armed. When it suspends, the timer is armed for what is left of [timeout],
 * counted from the call, and [block] is cancelled when it fires.
 *
 * The semantics are [withTimeoutOrNull]'s: null when [timeout] passed, the block's result or exception otherwise, an
 * external cancellation propagates, and [block] is waited for after it is cancelled. [block] must not answer null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T : Any> withLazyTimeoutOrNull(timeout: Duration, block: suspend () -> T): T? {
    val started = System.nanoTime()
    return coroutineScope {
        val running = async(start = CoroutineStart.UNDISPATCHED) { block() }
        if (running.isCompleted) {
            return@coroutineScope running.getCompleted()
        }
        val left = timeout - (System.nanoTime() - started).nanoseconds
        withTimeoutOrNull(left) { running.await() } ?: run {
            running.cancel()
            null
        }
    }
}
