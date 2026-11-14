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

package me.ahoo.cosec.social

import me.ahoo.cosec.annotations.CoSecSpi
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The OAuth states CoSec issued, each with the SHA-256 of the binding of the browser it was issued to
 * ([SocialAuthorization]). A state is single use: [take] removes it atomically, so of two callbacks holding it at most
 * one finds it. A store that cannot be read throws, so a sign-in fails rather than proceed unchecked.
 *
 * Both calls suspend and never block the caller's thread, which may be an event loop: an implementation over a blocking
 * client moves its calls to a dispatcher of its own, as `RedisSocialStateStore` does.
 */
@CoSecSpi
interface SocialStateStore {
    /** Records [state] with [bindingHash] for [lifetime]. */
    suspend fun put(state: String, bindingHash: String, lifetime: Duration)

    /** Removes [state] and returns its binding hash, or null when it was never issued, has expired or was taken. */
    suspend fun take(state: String): String?
}

/**
 * A [SocialStateStore] in this JVM's memory: for tests, and a single instance that issues and receives every callback.
 *
 * Anyone can start a sign-in, so [put] must stay cheap: it drops the expired states only once the store holds twice
 * what it held after the last sweep, and at least [sweepFloor], which keeps a put constant time on average.
 */
class InMemorySocialStateStore(private val sweepFloor: Int = SWEEP_FLOOR) : SocialStateStore {
    init {
        require(sweepFloor >= 1) { "The sweep floor is positive, not $sweepFloor." }
    }

    /** The clock the states expire by; tests move it. */
    internal var nanoTime: () -> Long = System::nanoTime

    private class Entry(val bindingHash: String, val expiresAt: Long)

    private val states = ConcurrentHashMap<String, Entry>()
    private val sweepAt = AtomicInteger(sweepFloor)
    private val sweepCount = AtomicInteger()

    /** How many states the store holds, expired ones included. */
    internal val size: Int get() = states.size

    /** How many times [put] has scanned the store. */
    internal val sweeps: Int get() = sweepCount.get()

    override suspend fun put(state: String, bindingHash: String, lifetime: Duration) {
        val now = nanoTime()
        states[state] = Entry(bindingHash, now + lifetime.toNanos())
        val threshold = sweepAt.get()
        // One caller claims the sweep; the others go on.
        if (states.size >= threshold && sweepAt.compareAndSet(threshold, Int.MAX_VALUE)) {
            sweepCount.incrementAndGet()
            states.values.removeIf { it.expiresAt - now <= 0 }
            sweepAt.set((states.size.toLong() * 2).coerceIn(sweepFloor.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }

    override suspend fun take(state: String): String? =
        states.remove(state)?.takeIf { it.expiresAt - nanoTime() > 0 }?.bindingHash

    companion object {
        /** The size below which a put never sweeps. */
        const val SWEEP_FLOOR: Int = 1024
    }
}
