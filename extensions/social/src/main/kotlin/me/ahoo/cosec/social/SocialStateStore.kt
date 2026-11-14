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

/**
 * The OAuth states CoSec issued, each with the SHA-256 of the binding of the browser it was issued to
 * ([SocialAuthorization]). A state is single use: [take] removes it atomically, so of two callbacks holding it at most
 * one finds it. A store that cannot be read throws, so a sign-in fails rather than proceed unchecked.
 *
 * Calls may block: `JustAuthProvider` makes them on its own dispatcher.
 */
@CoSecSpi
interface SocialStateStore {
    /** Records [state] with [bindingHash] for [lifetime]. */
    fun put(state: String, bindingHash: String, lifetime: Duration)

    /** Removes [state] and returns its binding hash, or null when it was never issued, has expired or was taken. */
    fun take(state: String): String?
}

/** A [SocialStateStore] in this JVM's memory: for tests, and a single instance that issues and receives every callback. */
class InMemorySocialStateStore : SocialStateStore {
    private class Entry(val bindingHash: String, val expiresAt: Long)

    private val states = ConcurrentHashMap<String, Entry>()

    override fun put(state: String, bindingHash: String, lifetime: Duration) {
        val now = System.nanoTime()
        states.values.removeIf { it.expiresAt - now < 0 }
        states[state] = Entry(bindingHash, now + lifetime.toNanos())
    }

    override fun take(state: String): String? =
        states.remove(state)?.takeIf { it.expiresAt - System.nanoTime() > 0 }?.bindingHash
}
