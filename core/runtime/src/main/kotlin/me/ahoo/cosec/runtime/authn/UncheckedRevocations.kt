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

package me.ahoo.cosec.runtime.authn

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The tokens [TokenAuthenticator] accepted without a revocation check, because the revocation store could not be read
 * (PIP-7, the documented exception to failing closed): counted ([count], the meter `cosec.revocation.unchecked` with
 * `cosec-micrometer`), and logged at `WARN` at most once per [interval], since during an outage every authenticated
 * request would log one. An operator alerts on it: while it rises, a revoked token is accepted.
 */
class UncheckedRevocations(private val interval: Duration = DEFAULT_INTERVAL) {
    private val accepted = AtomicLong()
    private val lastWarning = AtomicLong(Long.MIN_VALUE)

    /** How many tokens were accepted without a revocation check since this instance started. */
    val count: Long get() = accepted.get()

    internal fun record(unavailable: RevocationUnavailableException) {
        accepted.incrementAndGet()
        val now = System.nanoTime()
        val previous = lastWarning.get()
        if (previous != Long.MIN_VALUE && now - previous < interval.toNanos()) {
            return
        }
        if (lastWarning.compareAndSet(previous, now)) {
            log.warn(unavailable) {
                "Token revocations cannot be read: tokens are accepted without a revocation check ( so far)."
            }
        }
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(10)
        private val log = KotlinLogging.logger {}
    }
}
