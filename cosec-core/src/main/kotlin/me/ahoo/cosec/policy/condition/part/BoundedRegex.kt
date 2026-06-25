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

package me.ahoo.cosec.policy.condition.part

import java.util.concurrent.TimeUnit

/**
 * Thrown when a regular-expression match exceeds its allotted time budget.
 *
 * Guards [RegularConditionMatcher] against catastrophic backtracking (ReDoS): a policy-supplied
 * pattern matched against attacker-controlled request input could otherwise hang the worker thread
 * or the reactor event loop indefinitely. The match is aborted and propagates as an authorization
 * failure (fail-closed -> `IMPLICIT_DENY`), mirroring how a sandbox violation aborts the OGNL matcher.
 */
class RegexTimeoutException(message: String) : RuntimeException(message)

/**
 * Matches [input] against this [Regex], aborting with [RegexTimeoutException] if the match does not
 * complete within [timeoutMillis].
 *
 * The bound is enforced by feeding the matcher a [CharSequence] view that checks a deadline on every
 * character access; `java.util.regex` reads the input heavily while backtracking, so a runaway match
 * is interrupted promptly without spawning extra threads.
 */
fun Regex.matchesWithin(input: CharSequence, timeoutMillis: Long): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    return matches(DeadlineCharSequence(input, deadlineNanos))
}

/**
 * A read-only [CharSequence] decorator that throws [RegexTimeoutException] once [deadlineNanos]
 * (a `System.nanoTime()` reading) has passed. The subtraction comparison is overflow-safe across
 * `nanoTime` wraparound.
 */
internal class DeadlineCharSequence(
    private val delegate: CharSequence,
    private val deadlineNanos: Long
) : CharSequence {
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Char {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw RegexTimeoutException(
                "Regular expression match exceeded the time budget; aborted to prevent ReDoS."
            )
        }
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = delegate.toString()
}
