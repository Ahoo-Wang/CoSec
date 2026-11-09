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

package me.ahoo.cosec.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.EntryDigest
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * The digests of the control plane (#1217): [entry] is what a document diff computes for every policy and binding of
 * both revisions, so its fixed cost per small entry is multiplied by the size of the documents; [snapshot] is the text
 * form of a stored document's digest, written into every revision record key and index entry.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class EntryDigestBenchmark {
    private val policy = PolicyDefinition(
        PolicyId("read"),
        false,
        null,
        listOf(StatementSource("", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource("order/**")))),
    )
    private val digest = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { (it * 7).toByte() })
    private val text = digest.toString()

    @Benchmark
    fun entry(): String = EntryDigest.of(policy)

    @Benchmark
    fun snapshot(): String = digest.toString()

    @Benchmark
    fun parse(): SnapshotDigest = SnapshotDigest.parse(text)
}
