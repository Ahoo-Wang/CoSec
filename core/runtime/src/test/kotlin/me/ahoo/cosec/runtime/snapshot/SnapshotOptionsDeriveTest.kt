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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `SNP-23`: what the snapshot source's options no longer take as settings is derived from `revalidate-interval`, the
 * pipeline's timeout and the cores, and each formula is a safety property: a lag report is a count of missed passes, and
 * a cold load never waits as long as the request it serves.
 */
class SnapshotOptionsDeriveTest {
    @Test
    fun `the defaults derive the values the settings used to have`() {
        val derived = SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ofSeconds(5), cores = 8)
        derived.lagWarn.assert().isEqualTo(Duration.ofSeconds(60))
        derived.lagEscalate.assert().isEqualTo(Duration.ofMinutes(5))
        derived.coldLoadTimeout.assert().isEqualTo(Duration.ofSeconds(2))
        derived.compileThreads.assert().isEqualTo(2)
        derived.reloadConcurrency.assert().isEqualTo(SnapshotOptions.DEFAULT_RELOAD_CONCURRENCY)
    }

    @Test
    fun `a lag threshold is a count of missed passes, always above the interval`() {
        for (seconds in listOf(1L, 7L, 10L, 120L, 300L)) {
            val interval = Duration.ofSeconds(seconds)
            val derived = SnapshotOptions.derive(interval, Duration.ofSeconds(5))
            derived.lagWarn.assert().isEqualTo(interval.multipliedBy(SnapshotOptions.LAG_WARN_PASSES.toLong()))
            derived.lagEscalate.assert().isEqualTo(interval.multipliedBy(SnapshotOptions.LAG_ESCALATE_PASSES.toLong()))
            derived.lagWarn.assert().isGreaterThan(interval)
            derived.lagEscalate.assert().isGreaterThan(derived.lagWarn)
            // The options accept them: the interval is below the warning, the warning not above the escalation.
            SnapshotOptions(
                revalidateInterval = interval,
                lagWarn = derived.lagWarn,
                lagEscalate = derived.lagEscalate,
            )
        }
    }

    @Test
    fun `a cold load waits less than the request it serves, for every deadline`() {
        for (millis in listOf(3L, 50L, 125L, 1_000L, 5_000L, 60_000L)) {
            val timeout = Duration.ofMillis(millis)
            val derived = SnapshotOptions.derive(Duration.ofSeconds(10), timeout)
            derived.coldLoadTimeout.assert().describedAs("deadline $timeout").isLessThan(timeout)
            derived.coldLoadTimeout.assert().isGreaterThanOrEqualTo(Duration.ofMillis(1))
        }
        SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ofMillis(125)).coldLoadTimeout
            .assert().isEqualTo(Duration.ofMillis(50))
    }

    @Test
    fun `a cold load that would be under a millisecond is a millisecond, never zero`() {
        SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ofMillis(1)).coldLoadTimeout
            .assert().isEqualTo(Duration.ofMillis(1))
        SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ZERO).coldLoadTimeout
            .assert().isEqualTo(Duration.ofMillis(1))
    }

    @Test
    fun `the compile threads follow the cores, between one and four`() {
        mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 8 to 2, 12 to 3, 16 to 4, 64 to 4).forEach { (cores, threads) ->
            SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ofSeconds(5), cores).compileThreads
                .assert().describedAs("$cores cores").isEqualTo(threads)
        }
    }

    @Test
    fun `what was derived is said in the words of the formulas`() {
        SnapshotOptions.derive(Duration.ofSeconds(10), Duration.ofSeconds(5), cores = 8).toString().assert()
            .contains("lag-warn=PT1M (6 passes")
            .contains("lag-escalate=PT5M (30 passes")
            .contains("cold-load-timeout=PT2S (40 % of the pipeline timeout)")
            .contains("compile-threads=2")
            .contains("reload-concurrency=16 (constant)")
    }
}
