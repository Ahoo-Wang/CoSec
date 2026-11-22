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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * A measurement, not a check (R3-11, `CTL-74`, `CTL-75`): the stored size of an instance's status and of its resident
 * record, as the codecs write them, for tenant ids of three shapes and the target's resident counts. It is a `main` and
 * nothing JUnit discovers, so `check` compiles it and never runs it. It needs no Redis.
 *
 * Run: `nice -n 15 ./gradlew :cocache:testClasses`, then `java -cp <the cocache module's test runtime classpath>
 * me.ahoo.cosec.cocache.shadow.SaasScaleStatusMeasurementKt [file]` (default `status-sizes.txt`). Its output is in
 * `docs/reviews/2026-11/saas-status-split-sizes.txt`, of the commit that file names.
 */
fun main(args: Array<String>) {
    val at = Instant.parse("2026-11-22T00:00:00Z")
    val out = StringBuilder()
    fun line(text: String) {
        out.appendLine(text)
        println(text)
    }
    fun windows(n: Int) = (0 until n).map { "c${7_350_000_000_000_000_000L + it}@${1_000_000 + it}" }.toSet()
    line("status (what liveness and the acknowledgement read), bytes:")
    for (n in listOf(0, 20, 1_000)) {
        line(
            "  $n windows: ${InstanceStatusCodec.encode(
                InstanceStatus("gw-0123456789", at, windows = windows(n))
            ).size}"
        )
    }
    line("resident record (what propagation reads), bytes, platform + 12 apps + N tenants, revisions ~1e6:")
    val shapes = listOf<Pair<String, (Int) -> String>>(
        "t-<n>" to { "t-$it" },
        "16 chars" to { "tenant-%09d".format(it) },
        "UUID" to { UUID.nameUUIDFromBytes("$it".toByteArray()).toString() },
    )
    for ((label, id) in shapes) {
        val sizes = listOf(2_000, 10_000, 16_384).map { n ->
            val documents = buildMap<SnapshotKey, Long> {
                put(SnapshotKey.Platform, 1_234_567)
                repeat(12) { put(SnapshotKey.App("app-$it"), 234_567) }
                repeat(n) { put(SnapshotKey.Tenant(id(it)), 1_000_000L + it) }
            }
            "N=$n ${ResidentDocumentsCodec.encode(ResidentDocuments("gw-0123456789", at, documents)).size}"
        }
        line("  $label: ${sizes.joinToString("  ")}")
    }
    File(args.firstOrNull() ?: "status-sizes.txt").writeText(out.toString())
}
