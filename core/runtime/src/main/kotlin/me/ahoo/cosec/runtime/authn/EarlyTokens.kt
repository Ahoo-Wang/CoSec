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
import me.ahoo.cosec.spi.token.TokenNotYetValidException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Tokens refused because they are not valid yet (`IDN-29`): an issuer's clock runs ahead of this instance's by more
 * than the skew. A clock to fix, not a key: logged at `WARN` at most once per [INTERVAL], since every token of that
 * issuer would log one, and at `DEBUG` every time.
 */
internal object EarlyTokens {
    private val INTERVAL: Duration = Duration.ofMinutes(1)
    private const val NEVER = Long.MIN_VALUE
    private val lastWarning = AtomicLong(NEVER)
    private val log = KotlinLogging.logger(TokenAuthenticator::class.java.name)

    fun record(early: TokenNotYetValidException, now: Long = System.nanoTime()) {
        val previous = lastWarning.get()
        val due = previous == NEVER || now - previous >= INTERVAL.toNanos()
        if (due && lastWarning.compareAndSet(previous, now)) {
            log.warn { "Token refused as not valid yet: ${early.message} See $PAGE" }
        } else {
            log.debug { "Token refused as not valid yet: ${early.message}" }
        }
    }

    const val PAGE = "https://cosec.ahoo.me/operations/troubleshooting#token-invalid"
}
