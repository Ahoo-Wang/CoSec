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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.http

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a refusal's body is made (`PIP-34`), for the three adapters and the login routes alike: the host's
 * [renderer], or CoSec's own body when it has none, or when it throws or answers no bytes. A renderer's failure never
 * turns a refusal into a `500` or a pass: the response keeps the status and headers the adapter already set, with
 * CoSec's body. Each failure is counted ([failures]; the meter `cosec.problem.render.failed` with `cosec-micrometer`)
 * and logged at `WARN` at most once per [interval], at `DEBUG` every time, so a broken renderer never floods the log.
 */
@InternalCoSecApi
class ProblemBodies(
    val renderer: ProblemRenderer = ProblemRenderer.DEFAULT,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val failures = AtomicLong()
    private val lastWarning = AtomicLong(NEVER)

    /** How many refusals were answered with CoSec's body because [renderer] failed, since this instance started. */
    fun failures(): Long = failures.get()

    /** The body of the response that refuses a request with [problem]. */
    // stays: a renderer is the application's code, and every failure of it falls back to CoSec's body (PIP-34)
    @Suppress("TooGenericExceptionCaught")
    fun body(problem: Problem): ByteArray {
        if (renderer === ProblemRenderer.DEFAULT) {
            return ProblemEncoder.encode(problem)
        }
        return try {
            // A renderer written in Java may answer null: reading its size throws, as an empty answer is refused.
            renderer.render(problem).also { check(it.isNotEmpty()) { "The renderer answered no bytes." } }
        } catch (e: Exception) {
            failed(problem, e)
            ProblemEncoder.encode(problem)
        }
    }

    private fun failed(problem: Problem, failure: Exception) {
        failures.incrementAndGet()
        if (warns()) {
            log.warn(failure) { message(problem, failure) }
        } else if (log.isDebugEnabled()) {
            log.debug(failure) { message(problem, failure) }
        }
    }

    private fun message(problem: Problem, failure: Exception) =
        "Request [${problem.requestId}]: the ProblemRenderer [${renderer.javaClass.name}] failed with " +
            "${failure.javaClass.name}, so the ${problem.status} ${problem.type.slug} refusal is answered with CoSec's " +
            "own body (PIP-34). See $PAGE"

    private fun warns(): Boolean {
        val now = nanoTime()
        val previous = lastWarning.get()
        if (previous != NEVER && now - previous < interval.toNanos()) {
            return false
        }
        return lastWarning.compareAndSet(previous, now)
    }

    @InternalCoSecApi
    companion object {
        // Before DEFAULT, which is built with them: a companion initializes in declaration order.
        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(1)
        const val PAGE = "https://cosec.ahoo.me/architecture/extending#problem-renderer"
        private const val NEVER = Long.MIN_VALUE
        private val log = KotlinLogging.logger {}

        /** CoSec's own body, and nothing to count: what an adapter answers with unless the host renders its own. */
        @JvmField
        val DEFAULT: ProblemBodies = ProblemBodies()
    }
}
