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

import me.ahoo.cosec.annotations.InternalCoSecApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What each stage of a request may take, as a share of the one deadline the operator states, `cosec.pipeline.timeout`
 * (`PIP-33`; the rule of root `AGENTS.md`: what can be computed is not configured). A stage's own timeout decides which
 * failure the request is (503, an attribute that is unavailable, a rule `strictFailure` decides), never whether it ends:
 * the deadline ends it. Each share is a named constant; the shipped defaults were exactly these shares of the default
 * deadline of 5 s, so a host on the default deadline answers as before. A share is never below [FLOOR].
 *
 * - [authentication]: [AUTHENTICATION_PERCENT] % (1 s at 5 s);
 * - [membership]: [MEMBERSHIP_PERCENT] % (500 ms), also the fill of the membership cache;
 * - [attribute]: [ATTRIBUTE_PERCENT] % (300 ms), for an `AttributeProvider` that sets no timeout of its own;
 * - [rateLimit]: [RATE_LIMIT_PERCENT] % (500 ms);
 * - [revocationRead]: [REVOCATION_READ_PERCENT] % of [authentication] (300 ms), each read of CoSec's own revocation reader,
 *   so that a token's two reads end inside the authentication budget (`PIP-7`) for every deadline of 20 ms or more;
 * - [requiredListener]: [REQUIRED_LISTENER_PERCENT] % (1 s), one REQUIRED decision listener (`OBS-10`), so that its wait
 *   ends inside the request.
 */
@InternalCoSecApi
class StageBudgets private constructor(
    val timeout: Duration,
    val authentication: Duration,
    val membership: Duration,
    val attribute: Duration,
    val rateLimit: Duration,
    val revocationRead: Duration,
    val requiredListener: Duration,
) {
    override fun toString(): String =
        "authentication=$authentication ($AUTHENTICATION_PERCENT % of the pipeline timeout $timeout), " +
            "membership=$membership ($MEMBERSHIP_PERCENT %), attributes=$attribute ($ATTRIBUTE_PERCENT %), " +
            "rate-limit=$rateLimit ($RATE_LIMIT_PERCENT %), " +
            "revocation read=$revocationRead ($REVOCATION_READ_PERCENT % of the authentication budget), " +
            "required listener=$requiredListener ($REQUIRED_LISTENER_PERCENT %)"

    @InternalCoSecApi
    companion object {
        const val AUTHENTICATION_PERCENT: Int = 20
        const val MEMBERSHIP_PERCENT: Int = 10
        const val ATTRIBUTE_PERCENT: Int = 6
        const val RATE_LIMIT_PERCENT: Int = 10
        const val REVOCATION_READ_PERCENT: Int = 30
        const val REQUIRED_LISTENER_PERCENT: Int = 20

        /** The least a stage gets: a share of a very small deadline is never zero. */
        val FLOOR: Duration = 1.milliseconds

        /**
         * The share of Spring's `spring.lifecycle.timeout-per-shutdown-phase` one set of listeners has to deliver what
         * is queued at shutdown (`OBS-10`). The drains run one after another as the context closes: the decision
         * listeners once, the Kafka audit listener twice (its queue, then the broker's answers), the change listeners
         * once, the Kafka change listener twice. Six drains together take no longer than the phase; a sixth of
         * Spring's default 30 s is the 5 s they had.
         */
        const val DRAINS_PER_SHUTDOWN_PHASE: Int = 6

        private const val PERCENT = 100

        fun of(timeout: Duration): StageBudgets {
            val authentication = share(timeout, AUTHENTICATION_PERCENT)
            return StageBudgets(
                timeout = timeout,
                authentication = authentication,
                membership = share(timeout, MEMBERSHIP_PERCENT),
                attribute = share(timeout, ATTRIBUTE_PERCENT),
                rateLimit = share(timeout, RATE_LIMIT_PERCENT),
                revocationRead = share(authentication, REVOCATION_READ_PERCENT),
                requiredListener = share(timeout, REQUIRED_LISTENER_PERCENT),
            )
        }

        /** How long one set of listeners delivers what is queued at shutdown: [DRAINS_PER_SHUTDOWN_PHASE]. */
        fun drain(shutdownPhase: Duration): Duration = (shutdownPhase / DRAINS_PER_SHUTDOWN_PHASE).coerceAtLeast(FLOOR)

        private fun share(of: Duration, percent: Int): Duration = (of * percent / PERCENT).coerceAtLeast(FLOOR)
    }
}
