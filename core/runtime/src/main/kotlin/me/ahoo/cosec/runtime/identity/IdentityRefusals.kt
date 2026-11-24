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

package me.ahoo.cosec.runtime.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.ProblemType
import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The forwarded identities [IdentityGate] refuses, told to an operator without DEBUG (#1690). A service behind a gateway
 * that refuses one sees a hop that is misconfigured (the gateway's clock, its limits, its version) or a request that did
 * not come through the gateway. The response says only the problem; the reason is logged here: at `WARN` at most once
 * per [interval] for each problem type, so a flood of bad headers never floods the log, and at `DEBUG` every time. The
 * message is built only when it is logged.
 *
 * Every refusal is counted by its problem type ([count]; the meter `cosec.identity.refused` with `cosec-micrometer`),
 * the ones the bounded log leaves out included (`OBS-7`). In gateway mode there are no decision events, so this count is
 * the only metric of a forged or broken forwarded identity: any non-zero rate is a hop to look at.
 */
@InternalCoSecApi
class IdentityRefusals(
    private val interval: Duration = DEFAULT_INTERVAL,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lastWarnings = EnumMap<ProblemType, AtomicLong>(ProblemType::class.java).apply {
        TYPES.forEach { put(it, AtomicLong(NEVER)) }
    }

    private val counts = EnumMap<ProblemType, AtomicLong>(ProblemType::class.java).apply {
        TYPES.forEach { put(it, AtomicLong()) }
    }

    /** How many forwarded identities were refused with [type] since this instance started; 0 for a type not in [TYPES]. */
    fun count(type: ProblemType): Long = counts[type]?.get() ?: 0

    internal fun record(requestId: String, type: ProblemType, reason: String) {
        counts.getValue(type).incrementAndGet()
        if (warns(type)) {
            log.warn { message(requestId, type, reason) }
        } else if (log.isDebugEnabled()) {
            log.debug { message(requestId, type, reason) }
        }
    }

    private fun message(requestId: String, type: ProblemType, reason: String) =
        "Request [$requestId]: the forwarded identity is refused (${type.status} ${type.slug}), $reason. " +
            (if (type == ProblemType.UNAVAILABLE) OLDER_READER else HOP) + " See $PAGE"

    private fun warns(type: ProblemType): Boolean {
        val last = lastWarnings.getValue(type)
        val now = nanoTime()
        val previous = last.get()
        if (previous != NEVER && now - previous < interval.toNanos()) {
            return false
        }
        return last.compareAndSet(previous, now)
    }

    @InternalCoSecApi
    companion object {
        /** The problems the gate refuses an identity with: the closed set of the meter's `problem` tag. */
        val TYPES: Set<ProblemType> = setOf(
            ProblemType.INVALID_REQUEST,
            ProblemType.TOKEN_INVALID,
            ProblemType.TOKEN_EXPIRED,
            ProblemType.UNAVAILABLE,
        )

        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(1)

        private const val HOP = "A service behind a gateway refuses a CoSec-Identity header the gateway did not write, " +
            "or wrote with a clock or limits that disagree with this service's."

        /**
         * `IDN-41`: no store failed. A writer of a later version stated a member it counted on every reader to know, and
         * this one is older: the deployment runs a reader the writer did not count (RFC 0014 G3). Upgrade it.
         */
        private const val OLDER_READER = "A gateway or service of a later CoSec version wrote a member this service " +
            "must understand to decide as it meant, and this service is older than it counted on: upgrade this service."
        const val PAGE = "https://cosec.ahoo.me/operations/troubleshooting#a-service-behind-the-gateway-refuses-requests"
        private const val NEVER = Long.MIN_VALUE
        private val log = KotlinLogging.logger(IdentityGate::class.java.name)
    }
}
