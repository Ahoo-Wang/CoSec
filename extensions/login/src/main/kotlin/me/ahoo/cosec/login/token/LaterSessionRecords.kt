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

package me.ahoo.cosec.login.token

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.model.Identifiers
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * Tells an operator a session record a later version wrote (`SNP-27`), apart from a store that is broken: during a
 * rolling upgrade an older instance refuses such a record, answers `503` and keeps the session, which an upgraded
 * instance reads; nothing is wrong with the store. At `WARN` at most once per [INTERVAL], at `DEBUG` every time; every
 * refusal is counted by `cosec.stored.refused` (`OBS-12`).
 */
internal object LaterSessionRecords {
    private val INTERVAL: Duration = Duration.ofMinutes(1)
    private const val NEVER = Long.MIN_VALUE
    private val lastWarning = AtomicLong(NEVER)

    /** Whether [error], thrown by a session store asked to [operation], is a record a later version wrote; logged if so. */
    fun told(operation: String, error: Throwable, nanoTime: () -> Long = System::nanoTime): Boolean {
        val later = StoredFormat.later(error) ?: return false
        val now = nanoTime()
        val previous = lastWarning.get()
        val warns = (previous == NEVER || now - previous >= INTERVAL.toNanos()) && lastWarning.compareAndSet(previous, now)
        if (warns) {
            log.warn { message(operation, later) }
        } else {
            log.debug { message(operation, later) }
        }
        return true
    }

    private fun message(operation: String, later: StoredFormat.Later): String {
        val members = later.members.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " (members ", postfix = " in its crit)") { "[${Identifiers.printable(it)}]" }
            .orEmpty()
        return "The session store could not $operation: a later CoSec wrote the session record$members, which this " +
            "instance does not read and answers 503; the session is kept, and an upgraded instance reads it. Upgrade " +
            "this instance; nothing is wrong with the store."
    }
}
