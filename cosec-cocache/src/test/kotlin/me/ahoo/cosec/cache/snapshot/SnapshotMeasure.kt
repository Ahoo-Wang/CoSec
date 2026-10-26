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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.parity.SnapshotCompilation
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotChangeListener
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotRead
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `./gradlew :cosec-cocache:snapshotMeasure -PredisUrl=redis://localhost:6379 -PkeyPrefix=cosec [-PrequestsFile=<file>]
 * [-Pinstances=N] [-PrevalidateInterval=10s] [-ProundTrips=true] [-Poutput=<file>]` (RFC 0003 §4.8): sizes the
 * snapshot documents of a deployment's 7.x data, to settle Q7, Q14 and Q19 before the switch.
 *
 * It converts the data with [CoSec7xSnapshotReader], as `snapshotConvert -PdryRun=true` does, and measures each document:
 * - its size as JSON and as [SnapshotDocumentCodec] encodes it for CoCache, and its entries: policies, global policies and statements; an app's
 *   permissions, granted `(role, space)` pairs and spaces;
 * - its compile time, [COMPILE_RUNS] runs after [COMPILE_WARMUP] discarded ones, on this JVM (run it on JDK 25);
 * - the heap its compiled form holds: the heap used after a full GC, holding [RESIDENT_COPIES] compiled copies, minus
 *   the heap used before, per copy. An estimate, good to the tens of kilobytes;
 * - the Redis time conditional reads take ([RevalidationLoad]): each one hashes the whole value on Redis, so for
 *   `-Pinstances` instances revalidating every `-PrevalidateInterval`, Redis is busy for that share of its time.
 *
 * With `-ProundTrips=true` it also measures CoCache on that Redis, [ROUND_TRIPS] times each: a write, a cold read of
 * Redis, a read of the local copy, a conditional read whose value has not changed (which gives the Redis time per MB
 * measured on this Redis, next to CoCache's 2.5 ms), and how long another instance takes to hear of a write. These
 * write the documents under a fresh key prefix of their own (`<keyPrefix>:measure:<uuid>`) and invalidate them
 * afterwards; never point them at a production Redis. Without it, the tool only reads.
 *
 * With `-PrequestsFile` (the replay's `requests.jsonl`) it counts the distinct apps requested per 5 minutes and per hour,
 * the bound for the app documents an instance holds at once (Q19).
 *
 * Output: a summary on the console and `-Poutput` (`build/snapshot-measure/snapshot-measure.json` by default) with
 * counts, sizes, times and the SHA-256 of the input only: no policy, id or principal. Neither is committed, attached to
 * a PR or uploaded (RFC 0002 §8.2.1); paste the summary into the PR or an issue.
 */
object SnapshotMeasure {
    private const val PROPERTY_PREFIX = "cosec.snapshot.measure."
    const val COMPILE_RUNS = 20
    const val COMPILE_WARMUP = 10
    const val RESIDENT_COPIES = 10
    const val ROUND_TRIPS = 100

    /** RFC 0003 Q14: beyond these, a document is split into shards. */
    const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024
    const val MAX_COMPILE_P99_MILLIS = 200.0

    /** RFC 0003 §4.8: a round trip slower than this for the largest document goes back to the maintainer. */
    const val MAX_ROUND_TRIP_P99_MILLIS = 50.0

    private const val TOP_ROUND_TRIP_APPS = 5
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
    private val WINDOWS = mapOf("5m" to Duration.ofMinutes(5), "1h" to Duration.ofHours(1))

    class Options(
        val redisUrl: String,
        val keyPrefix: String = CoSec.COSEC,
        val requestsFile: Path? = null,
        val load: RevalidationLoad = RevalidationLoad(1, Duration.ofSeconds(10)),
        val roundTrips: Boolean = false,
        val output: Path = Path.of("build", "snapshot-measure", "snapshot-measure.json"),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val redisUrl = property("redisUrl") ?: fail("Pass the Redis to read: -PredisUrl=redis://host:port.")
        val options = Options(
            redisUrl = redisUrl,
            keyPrefix = property("keyPrefix") ?: CoSec.COSEC,
            requestsFile = property("requestsFile")?.let { Path.of(it) },
            load = RevalidationLoad(
                instances = property("instances")?.toInt() ?: 1,
                interval = property("revalidateInterval")?.let { Duration.parse("PT$it") } ?: Duration.ofSeconds(10),
            ),
            roundTrips = property("roundTrips").toBoolean(),
            output = property("output")?.let { Path.of(it) } ?: Path.of("build", "snapshot-measure", "snapshot-measure.json"),
        )
        if (run(options) != 0) {
            exitProcess(1)
        }
    }

    /** Measures, prints the summary and writes the report; 0, or 1 when the data cannot be converted. */
    fun run(options: Options, out: PrintStream = System.out): Int {
        Files.deleteIfExists(options.output)
        val uri = RedisURI.create(options.redisUrl)
        val conversion = SnapshotConvert.read(uri, options.keyPrefix)
        if (!conversion.accepted) {
            out.println(
                "The data has ${conversion.problems.size} problem(s) and cannot be converted: " +
                    "run snapshotConvert -PdryRun=true to list them.",
            )
            return 1
        }
        val report = measure(conversion, options, uri)
        options.output.toAbsolutePath().parent?.let(Files::createDirectories)
        CoSecJsonSerializer.writerWithDefaultPrettyPrinter().writeValue(options.output.toFile(), report)
        out.print(summary(report))
        out.println("Report: ${options.output.toAbsolutePath()}")
        return 0
    }

    fun measure(conversion: CoSec7xSnapshotReader.Conversion, options: Options, uri: RedisURI): ObjectNode {
        val documents = conversion.documents(publishedAt = Instant.now())
        val report = CoSecJsonSerializer.createObjectNode()
        report.put("inputSha256", conversion.inputSha256)
        report.put("warnings", conversion.warnings.size)
        report.put("javaVersion", Runtime.version().toString())
        val measured = conversion.contents.map { content -> measure(content, documents.getValue(content.key)) }
        val policies = measured.singleOrNull { it.key == SnapshotKey.Policies }
        val apps = measured.filter { it.key is SnapshotKey.App }
        if (policies == null) report.putNull("policies") else report.set("policies", policies.toJson())
        report.set("apps", appsJson(apps))
        report.set("revalidation", revalidation(options.load, documents.values))
        if (options.roundTrips) {
            val sampled = listOfNotNull(policies) + apps.sortedByDescending { it.jsonBytes }.take(TOP_ROUND_TRIP_APPS)
            report.set(
                "roundTrips",
                roundTrips(uri, options.keyPrefix, sampled, conversion.contents.associateBy { it.key })
            )
        }
        options.requestsFile?.let { report.set("activeApps", activeApps(it)) }
        report.set("verdict", verdict(measured, report.get("roundTrips")))
        return report
    }

    /** What one document measured. */
    class Measured(
        val key: SnapshotKey,
        val jsonBytes: Int,
        val encodedBytes: Int,
        /** Entry counts by name, see [counts]. */
        val counts: Map<String, Int>,
        val compileMillis: List<Double>,
        val residentBytes: Long,
    ) {
        fun toJson(): ObjectNode = CoSecJsonSerializer.createObjectNode().also { node ->
            node.put("jsonBytes", jsonBytes)
            node.put("encodedBytes", encodedBytes)
            counts.forEach { (name, count) -> node.put(name, count) }
            node.set("compileMillis", distribution(compileMillis))
            node.put("residentBytes", residentBytes)
        }
    }

    private val compilation = SnapshotCompilation()

    private fun measure(content: SnapshotContent, document: SnapshotDocument): Measured {
        val compileMillis = (0 until COMPILE_WARMUP + COMPILE_RUNS).map {
            val start = System.nanoTime()
            compilation.compile(content.key, document)
            (System.nanoTime() - start) / NANOS_PER_MILLI
        }.drop(COMPILE_WARMUP)
        return Measured(
            content.key,
            document.bodySize,
            SnapshotDocumentCodec.encode(document).size,
            counts(content),
            compileMillis,
            residentBytes(content.key, document),
        )
    }

    /** Entry counts, from the document's content as written. */
    internal fun counts(content: SnapshotContent): Map<String, Int> = when (content) {
        is SnapshotContent.Policies -> {
            val trees = content.policies.values.map(SnapshotContent::treeOf)
            mapOf(
                "policies" to trees.size,
                "globalPolicies" to trees.count { it.get("type")?.asString().equals("global", ignoreCase = true) },
                "statements" to trees.sumOf { it.get("statements")?.size() ?: 0 },
            )
        }

        is SnapshotContent.App -> {
            val appPermission = SnapshotContent.treeOf(content.appPermission)
            val spaces = content.grants.keys.mapTo(HashSet()) { it.substringAfter(SpacedRoleId.SPACE_ID_SEPARATOR, "") }
            mapOf(
                "permissions" to (appPermission.get("groups")?.values()?.sumOf { it.get("permissions")?.size() ?: 0 } ?: 0),
                "grantedRoles" to content.grants.size,
                "spaces" to spaces.size,
            )
        }
    }

    /** The heap [RESIDENT_COPIES] compiled copies hold, per copy, between two full collections. */
    private fun residentBytes(key: SnapshotKey, document: SnapshotDocument): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        fullGc()
        val before = memory.heapMemoryUsage.used
        val copies = List(RESIDENT_COPIES) { compilation.compile(key, document) }
        fullGc()
        val after = memory.heapMemoryUsage.used
        Reference.reachabilityFence(copies)
        return ((after - before) / RESIDENT_COPIES).coerceAtLeast(0)
    }

    @Suppress("ExplicitGarbageCollectionCall")
    private fun fullGc() {
        repeat(2) { System.gc() }
    }

    /** The app documents as distributions: one per app would name apps by their position only, never by id. */
    private fun appsJson(apps: List<Measured>): ObjectNode = CoSecJsonSerializer.createObjectNode().also { node ->
        node.put("count", apps.size)
        node.set("jsonBytes", distribution(apps.map { it.jsonBytes.toDouble() }))
        node.set("encodedBytes", distribution(apps.map { it.encodedBytes.toDouble() }))
        listOf("permissions", "grantedRoles", "spaces").forEach { name ->
            node.set(name, distribution(apps.map { it.counts.getValue(name).toDouble() }))
        }
        node.set("compileP99Millis", distribution(apps.map { percentile(it.compileMillis, 0.99) }))
        node.set("residentBytes", distribution(apps.map { it.residentBytes.toDouble() }))
    }

    private fun revalidation(load: RevalidationLoad, documents: Collection<SnapshotDocument>): ObjectNode =
        CoSecJsonSerializer.createObjectNode().also { node ->
            node.put("instances", load.instances)
            node.put("intervalMillis", load.interval.toMillis())
            node.put("totalBytes", documents.sumOf { it.bodySize.toLong() })
            node.put("millisPerMegabyte", RevalidationLoad.MILLIS_PER_MEGABYTE)
            node.put("passMillis", load.passMillis(documents))
            node.put("redisBusyFraction", load.busyFraction(documents))
        }

    /**
     * CoCache on the given Redis, for [sampled] documents, under a key prefix of their own: two instances (two
     * runtimes on one backend), one writing, the other reading and listening.
     */
    private fun roundTrips(
        uri: RedisURI,
        keyPrefix: String,
        sampled: List<Measured>,
        contents: Map<SnapshotKey, SnapshotContent>,
    ): ObjectNode {
        val scratchPrefix = "$keyPrefix:measure:${UUID.randomUUID()}"
        val node = CoSecJsonSerializer.createObjectNode()
        node.put("samples", ROUND_TRIPS)
        val perDocument = node.putArray("documents")
        RedisBackend.standalone(uri).use { backend ->
            SnapshotNode(backend, scratchPrefix).use { writer ->
                SnapshotNode(backend, scratchPrefix).use { reader ->
                    try {
                        sampled.forEach { measured ->
                            perDocument.add(roundTrips(writer, reader, measured, contents.getValue(measured.key)))
                        }
                    } finally {
                        sampled.forEach { writer.cache.invalidate(it.key) }
                    }
                }
            }
        }
        return node
    }

    private fun roundTrips(writer: SnapshotNode, reader: SnapshotNode, measured: Measured, content: SnapshotContent): ObjectNode {
        val key = measured.key
        val heard = java.util.concurrent.LinkedBlockingQueue<Long>()
        val subscription = reader.store.subscribe(object : SnapshotChangeListener {
            override fun onChanged(keys: Collection<SnapshotKey>) {
                if (key in keys) heard.add(System.nanoTime())
            }

            override fun onReset() = Unit
        })
        val put = ArrayList<Double>()
        val invalidation = ArrayList<Double>()
        val coldGet = ArrayList<Double>()
        val localGet = ArrayList<Double>()
        val unchanged = ArrayList<Double>()
        subscription.use {
            repeat(ROUND_TRIPS) { index ->
                // A new revision each time, so every write changes the value and is broadcast.
                val revised = content.write(index + 1L, Instant.now())
                heard.clear()
                val start = System.nanoTime()
                writer.cache.put(key, revised)
                put += (System.nanoTime() - start) / NANOS_PER_MILLI
                heard.poll(10, TimeUnit.SECONDS)?.let { invalidation += (it - start) / NANOS_PER_MILLI }
                coldGet += timed { reader.store.readIfChanged(key, null).get() }
                localGet += timed { writer.cache[key] }
                val digest = RedisSnapshotDocumentStore.digestOf(revised)
                unchanged += timed { check(reader.store.readIfChanged(key, digest).get() == SnapshotRead.Unchanged) }
            }
        }
        val megabytes = measured.encodedBytes / BYTES_PER_MEGABYTE
        return CoSecJsonSerializer.createObjectNode().also { node ->
            node.put("kind", key.kind.name)
            node.put("encodedBytes", measured.encodedBytes)
            node.set("putMillis", distribution(put))
            node.set("coldGetMillis", distribution(coldGet))
            node.set("localGetMillis", distribution(localGet))
            node.set("unchangedConditionalReadMillis", distribution(unchanged))
            node.set("invalidationMillis", distribution(invalidation))
            node.put("invalidationsHeard", invalidation.size)
            if (megabytes > 0) {
                node.put("unchangedConditionalReadMillisPerMegabyte", percentile(unchanged, 0.5) / megabytes)
            }
        }
    }

    private inline fun timed(action: () -> Unit): Double {
        val start = System.nanoTime()
        action()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    /** Distinct apps requested per window of `requests.jsonl`: per 5 minutes and per hour. */
    internal fun activeApps(requestsFile: Path): ObjectNode {
        val buckets = WINDOWS.mapValues { HashMap<Long, HashSet<String>>() }
        val all = HashSet<String>()
        var samples = 0L
        Files.newBufferedReader(requestsFile).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val event = CoSecJsonSerializer.readTree(line)
                val appId = event.get("request")?.get("appId")?.takeUnless { it.isNull }?.asString()
                    ?: return@forEach
                val at = instantOf(event.get("timestamp")).toEpochMilli()
                samples++
                all += appId
                WINDOWS.forEach { (name, window) ->
                    buckets.getValue(name).getOrPut(at / window.toMillis()) { HashSet() }.add(appId)
                }
            }
        }
        return CoSecJsonSerializer.createObjectNode().also { node ->
            node.put("samplesWithApp", samples)
            node.put("distinctApps", all.size)
            buckets.forEach { (name, windows) ->
                node.set(name, distribution(windows.values.map { it.size.toDouble() }))
            }
        }
    }

    private fun instantOf(node: JsonNode): Instant {
        if (node.isNumber) {
            val seconds = node.decimalValue()
            return Instant.ofEpochSecond(seconds.toLong(), seconds.remainder(BigDecimal.ONE).movePointRight(9).toLong())
        }
        return Instant.parse(node.asString())
    }

    /** RFC 0003 Q14: whether the two documents of the RFC suffice, or which ones need splitting. */
    private fun verdict(measured: List<Measured>, roundTrips: JsonNode?): ObjectNode {
        val oversized = measured.filter { it.encodedBytes > MAX_DOCUMENT_BYTES }
        val slow = measured.filter { percentile(it.compileMillis, 0.99) > MAX_COMPILE_P99_MILLIS }
        val slowRoundTrips = roundTrips?.get("documents")?.values()?.count { document ->
            listOf("putMillis", "coldGetMillis").any { document.get(it).get("p99").asDouble() > MAX_ROUND_TRIP_P99_MILLIS }
        } ?: 0
        return CoSecJsonSerializer.createObjectNode().also { node ->
            node.put("maxDocumentBytes", MAX_DOCUMENT_BYTES)
            node.put("maxCompileP99Millis", MAX_COMPILE_P99_MILLIS)
            node.put("policiesOverLimits", (oversized + slow).any { it.key == SnapshotKey.Policies })
            node.put("appsOverLimits", (oversized + slow).filter { it.key is SnapshotKey.App }.toSet().size)
            node.put("roundTripsOverLimit", slowRoundTrips)
            node.put("withinLimits", oversized.isEmpty() && slow.isEmpty() && slowRoundTrips == 0)
        }
    }

    fun summary(report: JsonNode): String = buildString {
        appendLine(
            "input sha256: ${report.get("inputSha256").asString()} (${report.get("warnings").asInt()} warning(s))"
        )
        report.get("policies").takeUnless { it.isNull }?.let { policies ->
            appendLine(
                "policies: ${policies.get("policies")} policies (${policies.get("globalPolicies")} global, " +
                    "${policies.get("statements")} statements), ${policies.get("jsonBytes")} bytes " +
                    "(${policies.get("encodedBytes")} stored), compile ${describe(policies.get("compileMillis"))} ms, " +
                    "~${policies.get("residentBytes")} bytes resident",
            )
        }
        val apps = report.get("apps")
        appendLine("apps: ${apps.get("count")} documents")
        listOf("jsonBytes", "permissions", "grantedRoles", "spaces", "compileP99Millis", "residentBytes").forEach {
            appendLine("  $it: ${describe(apps.get(it))}")
        }
        val revalidation = report.get("revalidation")
        appendLine(
            "revalidation: %.2f ms of Redis time per pass, x %d instance(s) every %d ms = %.2f%% of Redis's time".format(
                revalidation.get("passMillis").asDouble(),
                revalidation.get("instances").asInt(),
                revalidation.get("intervalMillis").asLong(),
                revalidation.get("redisBusyFraction").asDouble() * 100,
            ),
        )
        report.get("roundTrips")?.get("documents")?.values()?.forEach { document ->
            appendLine(
                "round trips (${document.get("kind").asString()}, ${document.get("encodedBytes")} bytes): " +
                    listOf("putMillis", "coldGetMillis", "localGetMillis", "unchangedConditionalReadMillis", "invalidationMillis")
                        .joinToString("; ") { "$it ${describe(document.get(it))}" },
            )
        }
        report.get("activeApps")?.let { active ->
            appendLine(
                "active apps: ${active.get("distinctApps")} distinct; per 5 minutes ${describe(active.get("5m"))}; " +
                    "per hour ${describe(active.get("1h"))}",
            )
        }
        val verdict = report.get("verdict")
        appendLine(
            if (verdict.get("withinLimits").asBoolean()) {
                "Within RFC 0003 Q14's limits: two documents suffice."
            } else {
                "OVER RFC 0003 Q14's limits: policies ${verdict.get("policiesOverLimits")}, " +
                    "apps ${verdict.get("appsOverLimits")}, round trips ${verdict.get("roundTripsOverLimit")} (§4.8)."
            },
        )
    }

    private fun describe(distribution: JsonNode): String =
        "p50 %.2f / p99 %.2f / max %.2f (n=%d)".format(
            distribution.get("p50").asDouble(),
            distribution.get("p99").asDouble(),
            distribution.get("max").asDouble(),
            distribution.get("count").asInt(),
        )

    internal fun distribution(values: List<Double>): ObjectNode = CoSecJsonSerializer.createObjectNode().also { node ->
        node.put("count", values.size)
        node.put("p50", percentile(values, 0.5))
        node.put("p99", percentile(values, 0.99))
        node.put("max", values.maxOrNull() ?: 0.0)
        node.put("total", values.sum())
    }

    /** The nearest-rank percentile; 0 for no values. */
    internal fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun property(name: String): String? = System.getProperty(PROPERTY_PREFIX + name)?.takeIf { it.isNotBlank() }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
