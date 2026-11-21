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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A read of the store that every caller on this control host shares (`CTL-62`, `CTL-68`): made at most once per
 * [maxAge], one read for the calls that wait for it, its value kept with when it was read. A read that fails is never
 * kept and never answered from an older one: the call fails as [load] failed, and the next call reads again (`CTL-52`).
 * For reads whose answer is the whole of a large stored value (every instance's status, every document's key), where a
 * few seconds of age cost less than reading it per call.
 */
internal class SharedRead<T>(
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
    private val load: suspend () -> T,
) {
    /** What one read answered, and when it was read. */
    class Read<T>(val value: T, val asOf: Instant)

    private val lock = Mutex()

    @Volatile
    private var last: Read<T>? = null

    suspend fun read(): Read<T> = fresh() ?: lock.withLock {
        fresh() ?: clock.instant().let { asOf -> Read(load(), asOf) }.also { last = it }
    }

    private fun fresh(): Read<T>? = last?.takeIf { Duration.between(it.asOf, clock.instant()) < maxAge }

    companion object {
        /**
         * How old a shared read may be: 5 seconds. Well under the minute after which an instance's status is ignored,
         * and short against how often a person or a service acts on what a listing shows; long against how often a
         * caller pages or polls, which is what it saves (a full paging of 50,000 documents at the default limit is
         * thousands of pages, one read).
         */
        val MAX_AGE: Duration = Duration.ofSeconds(5)
    }
}
