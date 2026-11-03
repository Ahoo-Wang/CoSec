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

package me.ahoo.cosec.cache.snapshot

import io.lettuce.core.RedisClient
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What one revalidation costs Redis per document size (RC1-50, roadmap §7): CoCache 6's conditional read hashes the
 * whole stored value with `redis.sha1hex` in a Lua script on Redis's main thread to compare digests, so an unchanged
 * document still costs time in proportion to its size. Not part of `check`: `./gradlew :cocache:measureConditionalRead`
 * against a Redis on localhost:6379, on a quiet machine. It prints, and writes to `build/conditional-read.md`, per size:
 * the Redis server time per call (`INFO commandstats`, the delta of the scripting commands) and the client's p50 and p99.
 */
@EnabledIfSystemProperty(named = "cosec.measure.conditionalRead", matches = "true")
class ConditionalReadCostMeasurement {
    @Test
    fun measure() {
        val client = RedisClient.create("redis://localhost:6379")
        val rows = ArrayList<String>()
        try {
            client.connect().use { connection ->
                val redis = connection.sync()
                rows += "Redis ${serverField(redis.info("server"), "redis_version")}, " +
                    "${Runtime.getRuntime().availableProcessors()} CPUs (${System.getProperty("os.name")} " +
                    "${System.getProperty("os.arch")}), $CALLS calls per size after $WARMUP to warm up."
                rows += ""
                rows += "| Document | Redis time per call | Client p50 | Client p99 |"
                rows += "|---|---|---|---|"
                RedisTarget.REDIS.backend().use { backend ->
                    SnapshotNode(backend, newKeyPrefix()).use { node ->
                        for (size in SIZES) {
                            rows += row(node, redis, size)
                        }
                    }
                }
            }
        } finally {
            client.shutdown()
        }
        val table = rows.joinToString("\n")
        println(table)
        Files.writeString(Path.of("build", "conditional-read.md"), table + "\n")
    }

    private fun row(
        node: SnapshotNode,
        redis: io.lettuce.core.api.sync.RedisCommands<String, String>,
        size: Int,
    ): String {
        val key = SnapshotKey.App("measured-$size")
        val document = document(size)
        node.cache.put(key, document)
        val digest = RedisSnapshotDocumentStore.digestOf(document)
        fun read() = node.store.readIfChanged(key, digest).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        read().assert().isEqualTo(SnapshotRead.Unchanged)
        repeat(WARMUP) { read() }
        val before = scriptingStats(redis.info("commandstats"))
        val latencies = LongArray(CALLS)
        for (i in 0 until CALLS) {
            val begin = System.nanoTime()
            read()
            latencies[i] = System.nanoTime() - begin
        }
        val after = scriptingStats(redis.info("commandstats"))
        val calls = after.first - before.first
        val serverMicros = (after.second - before.second).toDouble() / calls
        latencies.sort()
        val p50 = latencies[CALLS / 2] / NANOS_PER_MICRO
        val p99 = latencies[CALLS * 99 / 100] / NANOS_PER_MICRO
        return "| ${label(document.bodyBytes().size)} | ${"%.1f".format(serverMicros)} µs | $p50 µs | $p99 µs |"
    }

    /** Calls and microseconds of the scripting commands (`eval*`, `fcall*`), summed. */
    private fun scriptingStats(info: String): Pair<Long, Long> {
        var calls = 0L
        var usec = 0L
        for (line in info.lineSequence()) {
            val name = line.substringAfter("cmdstat_", "").substringBefore(':')
            if (name.startsWith("eval") || name.startsWith("fcall")) {
                val fields = line.substringAfter(':').split(',').associate { it.substringBefore('=') to it.substringAfter('=') }
                calls += fields.getValue("calls").toLong()
                usec += fields.getValue("usec").toLong()
            }
        }
        return calls to usec
    }

    private fun serverField(info: String, field: String): String =
        info.lineSequence().firstOrNull { it.startsWith("$field:") }?.substringAfter(':')?.trim() ?: "?"

    /** A platform-shaped document of about [size] bytes: policies of one statement each. */
    private fun document(size: Int): SnapshotDocument {
        val policies = StringBuilder()
        var i = 0
        while (policies.length < size) {
            if (i > 0) policies.append(',')
            policies.append("""{"id":"p$i","statements":[{"effect":"deny","action":"http:GET","resource":"http:path:/r$i/**"}]}""")
            i++
        }
        val body = """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED","policies":[$policies],"bindings":[]}"""
        return SnapshotDocument(2, 1, Instant.parse(PUBLISHED), body.encodeToByteArray())
    }

    private fun label(bytes: Int): String = if (bytes >= MIB) "%.1f MiB".format(bytes.toDouble() / MIB) else "${bytes / KIB} KiB"

    private companion object {
        const val KIB = 1024
        const val MIB = 1024 * 1024
        val SIZES = listOf(KIB, 16 * KIB, 128 * KIB, MIB, 4 * MIB)
        const val WARMUP = 50
        const val CALLS = 500
        const val READ_TIMEOUT_SECONDS = 30L
        const val NANOS_PER_MICRO = 1000
        const val PUBLISHED = "2026-11-03T00:00:00Z"
    }
}
