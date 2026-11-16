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

package me.ahoo.cosec.runtime.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * [KeyTransitions] run by a literal runner over seeded scripts on one key (RFC 0013 B2b, step 1): the key-level driver
 * of the slot model, beside [KeyTransitionsOrderTest]'s exhaustive small orders. Each seed is one [KeyModelScript] run
 * by [KeyModelRun] on [SlotModelThreads] alone, so it replays exactly: `-PpropertySeed=<seed>`. Under `propertyStress`
 * the seeds scale as the model's do.
 */
class KeyModelPropertyTest {
    @Test
    fun theTransitionsKeepEveryWaiterAndReadInEveryScriptedOrder() {
        val logger = LoggerFactory.getLogger(KeyModelPropertyTest::class.java.packageName) as Logger
        val level = logger.level
        logger.level = Level.OFF
        try {
            runSeeds()
        } finally {
            logger.level = level
        }
    }

    private fun runSeeds() {
        val only = System.getProperty("cosec.property.seed")?.toInt()
        val seeds = if (only != null) listOf(only) else (0 until maxOf(SEEDS, Integer.getInteger("cosec.property.iterations", 0) / 10)).toList()
        val totals = sortedMapOf<String, Int>()
        for (seed in seeds) {
            val run = KeyModelRun(KeyModelScript.generate(seed, EVENTS)).run()
            run.failure?.let { failure ->
                throw AssertionError(
                    buildString {
                        appendLine("Seed $seed: $failure")
                        run.log.forEach { appendLine("  $it") }
                        append("Replay: ./gradlew :runtime:test --tests '${KeyModelPropertyTest::class.qualifiedName}' -PpropertySeed=$seed")
                    }
                )
            }
            run.counts.forEach { (what, count) -> totals.merge(what, count, Int::plus) }
        }
        println("Key model: ${seeds.size} seeds; checked $totals")
        if (only == null) {
            VACUITY.forEach { check((totals[it] ?: 0) > 0) { "no seed reached '$it': $totals" } }
        }
    }

    private companion object {
        const val SEEDS = 500
        const val EVENTS = 120
        val VACUITY = listOf(
            "steps checked", "installs", "answered its handle", "answered directly", "answered timeout", "answered refused",
            "answered skipped", "redispatched", "eviction claimed", "eviction refused", "event ReadTimedOut", "event ReadFailed",
            "event CompileCrashed", "event CompileRejected", "event RetryDue", "event Close", "event Recompile", "event Changed",
        )
    }
}
