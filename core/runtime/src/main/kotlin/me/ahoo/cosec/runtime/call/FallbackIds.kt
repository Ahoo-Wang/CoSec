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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.time.Duration

/**
 * The ids of [ids], or a random one when it throws (round 2 M6): an id only names a request or an event, so a generator
 * that fails (CosId's `ClockBackwardsException` after its clock went back, a lost machine slot) must not take the
 * request's answer with it. Without this the exception left `Pipeline.evaluate` before any outcome: no problem body, no
 * `CoSec-Request-Id`, no decision event, and each adapter answered its own way. The failure is logged at most once
 * per [interval]. An [Error] goes through, as everywhere in the pipeline.
 */
internal class FallbackIds(private val ids: IdGenerator, interval: Duration = Duration.ofSeconds(10)) {
    private val reports = Throttle(interval)

    @Suppress("TooGenericExceptionCaught")
    fun generate(): String = try {
        ids.generate()
    } catch (e: Exception) {
        if (reports.allow(Unit)) {
            log.warn(e) { "The IdGenerator [$ids] failed: a random id names the request or event instead." }
        }
        RandomIdGenerator.generate()
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
