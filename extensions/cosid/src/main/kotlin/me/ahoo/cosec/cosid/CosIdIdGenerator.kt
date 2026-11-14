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

package me.ahoo.cosec.cosid

import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosid.CosIdException
import me.ahoo.cosid.StringIdGenerator
import me.ahoo.cosid.runtime.CosIdRuntime
import me.ahoo.cosid.runtime.RuntimeSpec
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * CoSec's ids from a CosId 5 generator ([IdGenerator]): request, decision event, token (`jti`) and control-plane
 * change ids, each the generator's next id as its codec writes it.
 *
 * An id goes into headers, URLs and Redis keys, so it must be plain: 1 to 64 characters of `A-Z a-z 0-9 . _ -`. A
 * sample is checked here, so that a codec writing anything else (a `:` prefix, say) fails startup rather than every
 * request. CoSec never uses these ids as secrets.
 *
 * [generate] runs on the request's thread, an event loop included, and must not wait (round 2 M6). Pick a time-based
 * generator (Snowflake, CosId80) with the clock regression policy that fails rather than waits
 * (`ClockRegressionPolicy.FAIL`, a wait of zero): CosId's default waits up to a second when the clock goes back, and a
 * segment generator waits for its store when its range runs out. CosId 5 does not expose a generator's policy, so CoSec
 * cannot check it. A generator that fails (`ClockBackwardsException`, a lost slot, an unavailable range) is never the
 * request's failure: a random id ([RandomIdGenerator]) names the request, event, token or change instead, and the
 * failure is logged at most every ten seconds.
 */
class CosIdIdGenerator(private val ids: StringIdGenerator) : IdGenerator {
    init {
        val sample = ids.generate()
        require(PLAIN.matches(sample)) {
            "A CosId generator for CoSec must make ids of 1 to 64 characters of A-Z a-z 0-9 . _ -, not [$sample]."
        }
    }

    private val lastFailure = AtomicLong(Long.MIN_VALUE)

    override fun generate(): String = try {
        ids.generate()
    } catch (e: CosIdException) {
        report(e)
        RandomIdGenerator.generate()
    }

    private fun report(failure: CosIdException) {
        val now = System.nanoTime()
        val previous = lastFailure.get()
        if (previous != Long.MIN_VALUE && now - previous < FAILURE_LOG_INTERVAL.toNanos()) {
            return
        }
        if (lastFailure.compareAndSet(previous, now)) {
            log.log(System.Logger.Level.WARNING, "The CosId generator failed: a random id is used instead.", failure)
        }
    }

    companion object {
        private val PLAIN = Regex("[A-Za-z0-9._-]{1,64}")
        private val FAILURE_LOG_INTERVAL = Duration.ofSeconds(10)

        /** The JDK's logger: this module brings no logging library; Spring Boot routes it to the application's logging. */
        private val log: System.Logger = System.getLogger(CosIdIdGenerator::class.java.name)

        /** The generator [name] of [runtime], with its codec: CosId's `default` unless named. */
        @JvmStatic
        @JvmOverloads
        fun of(runtime: CosIdRuntime, name: String = RuntimeSpec.DEFAULT_GENERATOR): CosIdIdGenerator =
            CosIdIdGenerator(runtime.stringGenerator(name))
    }
}
