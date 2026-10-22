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
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.snapshot.SnapshotDocument
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/**
 * `./gradlew :cosec-cocache:snapshotConvert -PredisUrl=redis://localhost:6379 -PkeyPrefix=cosec [-PdryRun=true]
 * [-Pinstances=N] [-PrevalidateInterval=10s]` (RFC 0003 §4.7): reads the 7.x data under the key prefix with
 * [CoSec7xReader], prints what it found, and unless the run is dry or the data has problems, publishes the documents
 * next to the 7.x keys, which it never deletes. Standalone Redis only. For development deployments of `main`, the gate
 * and sizing; not a user-facing migration.
 *
 * It writes no schema marker: the `"8"` marker belongs to RFC 0003 PR-5, which makes CoSec read the documents.
 */
object SnapshotConvert {
    private const val PROPERTY_PREFIX = "cosec.snapshot.convert."
    private const val SCAN_COUNT = 1000L

    @JvmStatic
    fun main(args: Array<String>) {
        val redisUrl = property("redisUrl") ?: fail("Pass the Redis to read: -PredisUrl=redis://host:port.")
        val keyPrefix = property("keyPrefix") ?: CoSec.COSEC
        val load = RevalidationLoad(
            instances = property("instances")?.toInt() ?: 1,
            interval = property("revalidateInterval")?.let { Duration.parse("PT$it") } ?: Duration.ofSeconds(10),
        )
        val uri = RedisURI.create(redisUrl)
        val conversion = read(uri, keyPrefix)
        report(conversion, load)
        if (property("dryRun").toBoolean()) {
            println("Dry run: nothing written.")
            return
        }
        publish(uri, keyPrefix, conversion)
    }

    private fun read(uri: RedisURI, keyPrefix: String): CoSec7xReader.Conversion =
        RedisClient.create(uri).use { client ->
            client.connect().use { connection ->
                RedisBackend.standalone(uri).use { backend ->
                    CoSec7xReader(backend, keyPrefix).use { reader ->
                        reader.read(CoSec7xReader.keys(keyPrefix) { pattern -> scan(connection.sync(), pattern) })
                    }
                }
            }
        }

    /** Prints what was found; exits when the conversion has problems. */
    private fun report(conversion: CoSec7xReader.Conversion, load: RevalidationLoad) {
        conversion.warnings.forEach { println("WARN  $it") }
        conversion.problems.forEach { println("ERROR $it") }
        println("input sha256: ${conversion.inputSha256}")
        if (!conversion.accepted) {
            fail("${conversion.problems.size} problem(s): fix the data, then run the conversion again.")
        }
        val documents = conversion.documents(publishedAt = Instant.now())
        conversion.contents.forEach { content ->
            val document = documents.getValue(content.key)
            println("${content.key}: ${content.entries()} entries, ${document.bodySize} bytes")
        }
        println(load.describe(documents.values))
    }

    private fun publish(uri: RedisURI, keyPrefix: String, conversion: CoSec7xReader.Conversion) {
        val spec = CoSecCacheSpecs.policySnapshot(keyPrefix)
        RedisBackend.standalone(uri).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val publisher = RedisSnapshotPublisher(runtime.createPolicySnapshotCache(spec))
                conversion.contents.forEach { publisher.replace(it).block() }
            }
        }
        println("Published ${conversion.contents.size} document(s) under ${spec.namespace}.")
    }

    private fun SnapshotContent.entries(): Int = when (this) {
        is SnapshotContent.Policies -> policies.size
        is SnapshotContent.App -> 1 + grants.size
    }

    private fun scan(commands: RedisCommands<String, String>, pattern: String): Sequence<String> = sequence {
        val args = ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT)
        var cursor = commands.scan(ScanCursor.INITIAL, args)
        while (true) {
            yieldAll(cursor.keys)
            if (cursor.isFinished) {
                break
            }
            cursor = commands.scan(cursor, args)
        }
    }

    private fun property(name: String): String? = System.getProperty(PROPERTY_PREFIX + name)?.takeIf { it.isNotBlank() }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}

/**
 * The Redis cost of revalidating these documents (RFC 0003 §4.8, Q14). A conditional read reads the whole value into
 * Lua and hashes it on Redis, on every call, while Redis serves nothing else: CoCache measured about 20 ms for 8 MB on
 * Redis 7.4 (13 ms to read the value, 7 ms of SHA-1). Every instance revalidates every document it holds once per
 * [interval], so Redis is busy for about (total size × [instances] ÷ [interval]) × [MILLIS_PER_MEGABYTE].
 */
class RevalidationLoad(val instances: Int, val interval: Duration) {
    init {
        require(instances > 0) { "instances must be positive." }
        require(!interval.isNegative && !interval.isZero) { "interval must be positive." }
    }

    /** Milliseconds of Redis time one pass over [documents] costs (one instance). */
    fun passMillis(documents: Collection<SnapshotDocument>): Double =
        documents.sumOf { it.bodySize.toLong() } / BYTES_PER_MEGABYTE * MILLIS_PER_MEGABYTE

    /** The share of Redis's time that [instances] revalidating [documents] every [interval] take. */
    fun busyFraction(documents: Collection<SnapshotDocument>): Double =
        passMillis(documents) * instances / interval.toMillis()

    fun describe(documents: Collection<SnapshotDocument>): String =
        "revalidation: %.2f ms of Redis time per pass, x %d instance(s) every %s = %.2f%% of Redis's time".format(
            passMillis(documents),
            instances,
            interval,
            busyFraction(documents) * PERCENT,
        )

    companion object {
        /** CoCache's measurement for a conditional read (Redis 7.4, 8 MB value: 20 ms). */
        const val MILLIS_PER_MEGABYTE = 2.5
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private const val PERCENT = 100.0
    }
}
