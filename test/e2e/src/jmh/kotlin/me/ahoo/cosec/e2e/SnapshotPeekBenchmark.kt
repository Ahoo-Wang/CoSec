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

package me.ahoo.cosec.e2e

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.Snapshot
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads

/**
 * A warm `SnapshotSource.peek` of the platform and one app, through the starter's wiring with a `MeterRegistry`: two
 * `onLoad` events on the request's thread. Where the starter meters the source (OBS-5) each is an increment of a
 * counter resolved at startup; where it does not, the source's metrics are `NOOP`. On one thread, and on eight
 * contending for the same counters.
 */
@State(Scope.Benchmark)
open class SnapshotPeekBenchmark {
    private lateinit var slice: Slice

    @Setup(Level.Trial)
    fun setup() {
        slice = Slice(options = SnapshotOptions(), meterRegistry = SimpleMeterRegistry())
        slice.publish(SnapshotKey.Platform, "")
        slice.publish(SnapshotKey.App(APP), "")
        checkNotNull(peek()) { "The snapshot is not installed" }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        slice.close()
    }

    @Benchmark
    @Threads(1)
    fun peek(): Snapshot<*, *>? = slice.source.peek(APP)

    @Benchmark
    @Threads(8)
    fun peekThreads8(): Snapshot<*, *>? = slice.source.peek(APP)

    private companion object {
        const val APP = "app"
    }
}
