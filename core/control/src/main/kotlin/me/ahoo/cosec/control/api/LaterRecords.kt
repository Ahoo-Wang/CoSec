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

package me.ahoo.cosec.control.api

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.model.Identifiers
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * Tells an operator a stored record a later version wrote (`SNP-27`), apart from a store that is broken: during a
 * rolling upgrade an older control-plane instance refuses such a record and answers `503`, never rewriting it; an
 * upgraded instance reads it. At `WARN` at most once per [INTERVAL], at `DEBUG` every time; every refusal is counted by
 * `cosec.stored.refused` (`OBS-12`).
 */
internal object LaterRecords {
    private val INTERVAL: Duration = Duration.ofMinutes(1)
    private const val NEVER = Long.MIN_VALUE
    private val lastWarning = AtomicLong(NEVER)

    /** Whether [error], which failed the call [call], is a record a later version wrote; logged if so. */
    fun told(call: String, error: Throwable, nanoTime: () -> Long = System::nanoTime): Boolean {
        val later = StoredFormat.later(error) ?: return false
        val now = nanoTime()
        val previous = lastWarning.get()
        val warns = (previous == NEVER || now - previous >= INTERVAL.toNanos()) && lastWarning.compareAndSet(previous, now)
        if (warns) {
            log.warn { message(call, later) }
        } else {
            log.debug { message(call, later) }
        }
        return true
    }

    private fun message(call: String, later: StoredFormat.Later): String {
        val record = later.record?.let { " (the ${it.tag} record)" }.orEmpty()
        val members = later.members.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = ", members ", postfix = " in its crit") { "[${Identifiers.printable(it)}]" }
            .orEmpty()
        return "$call is 503: a later CoSec wrote a stored record$record$members, which this instance does not read " +
            "and never rewrites; an upgraded instance reads it. Upgrade this instance; nothing is wrong with the store."
    }
}
