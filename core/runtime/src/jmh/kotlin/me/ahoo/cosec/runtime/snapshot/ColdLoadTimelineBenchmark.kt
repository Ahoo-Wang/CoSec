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

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * The cold-path criterion's timeline (RFC 0013 §5.4): one invocation runs [ColdLoadTimeline] for `Slot` and the runner
 * on this host and prints it between `TIMELINE BEGIN` and `TIMELINE END` in the fork's output, with the runner's extra
 * over the two windows and a stamp's cost. Its score is only the invocation's time. Run it with
 * `jmh-ab.yml` as `scripts/jmh-ab/README.md` says.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ColdLoadTimelineBenchmark {
    @Benchmark
    fun timeline(): Int {
        val report = ColdLoadTimeline.both(LOADS, WARMUP)
        println("TIMELINE BEGIN\n$report\nTIMELINE END")
        return report.length
    }

    private companion object {
        const val LOADS = 10_000
        const val WARMUP = 20_000
    }
}
