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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The principals the loader answered "unknown" for, each for a short [life] on this instance only (R2C-18): a token of a
 * removed member stays valid until it expires, and without this every request of it would reach the application's
 * loader. Only the loader's own "unknown" is recorded, never a failure or a timeout, so an outage never reads as "no
 * such principal". It holds at most [maxSize] keys: past that, a key is simply not recorded and its requests reach the
 * loader as they did before, so a flood of distinct principals cannot grow it.
 */
internal class UnknownPrincipals(
    private val life: Duration,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lifeNanos = life.toNanos()
    private val expiresAt = ConcurrentHashMap<String, Long>()

    /** Whether [key] was answered unknown within [life]. */
    fun contains(key: String): Boolean {
        if (lifeNanos == 0L) return false
        val expiry = expiresAt[key] ?: return false
        if (expiry - nanoTime() > 0) return true
        expiresAt.remove(key, expiry)
        return false
    }

    /** Records [key] as unknown for [life], unless the set is full of live entries. */
    fun remember(key: String) {
        if (lifeNanos == 0L) return
        val now = nanoTime()
        if (expiresAt.size >= maxSize) {
            expiresAt.values.removeIf { it - now <= 0 }
            if (expiresAt.size >= maxSize) return
        }
        expiresAt[key] = now + lifeNanos
    }

    /** Forgets [key], as `invalidate` of a principal's memberships does. */
    fun forget(key: String) {
        expiresAt.remove(key)
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 10_000
    }
}
