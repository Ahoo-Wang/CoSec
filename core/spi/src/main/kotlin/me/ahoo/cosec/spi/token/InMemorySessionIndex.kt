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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * A [SessionIndex] in this JVM's memory. A principal's entry is released once it has outlived every session it counts
 * (its `keepFor`), a bounded number per call and without a thread, so a long-running process does not keep an entry for
 * every principal that ever signed in.
 */
@InternalCoSecApi
class InMemorySessionIndex(private val now: () -> Instant = Instant::now) : SessionIndex {
    /** One principal's sessions in one tenant, oldest first, and when the entry may be released. */
    private class Sessions(val ids: ArrayDeque<String>, var keptUntil: Instant)

    private data class Owner(val tenant: String, val principal: String)

    private data class Release(val at: Instant, val owner: Owner)

    private val sessions = ConcurrentHashMap<Owner, Sessions>()
    private val releases = ConcurrentSkipListSet<Release>(compareBy<Release>({ it.at }, { it.owner.toString() }))

    /** How many principals the index holds an entry for. */
    internal val owners: Int get() = sessions.size

    override suspend fun admit(family: TokenFamily, now: Instant, keepFor: Duration, limit: Int): List<String> {
        release()
        val owner = Owner(family.tenant.value, family.principal.value)
        val until = now.plus(keepFor)
        val pushedOut = mutableListOf<String>()
        sessions.compute(owner) { _, existing ->
            val entry = existing ?: Sessions(ArrayDeque(), until)
            family.root?.let { root -> if (entry.ids.remove(root)) entry.ids.addLast(root) }
            entry.ids.remove(family.id)
            entry.ids.addLast(family.id)
            while (entry.ids.size > limit) pushedOut += entry.ids.removeFirst()
            if (until.isAfter(entry.keptUntil)) entry.keptUntil = until
            releases.add(Release(entry.keptUntil, owner))
            entry
        }
        return pushedOut
    }

    /** Frees up to [RELEASED_PER_CALL] entries that have outlived their sessions. */
    private fun release() {
        val at = now()
        val iterator = releases.iterator()
        var freed = 0
        while (freed < RELEASED_PER_CALL && iterator.hasNext()) {
            val release = iterator.next()
            if (release.at.isAfter(at)) return
            releases.remove(release)
            sessions.computeIfPresent(release.owner) { _, entry -> if (entry.keptUntil.isAfter(at)) entry else null }
            freed++
        }
    }

    private companion object {
        const val RELEASED_PER_CALL = 16
    }
}
