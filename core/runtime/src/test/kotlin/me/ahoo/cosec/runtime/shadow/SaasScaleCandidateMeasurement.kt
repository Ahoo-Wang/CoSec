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

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * A measurement, not a check: the candidate reads of shadow sampling at SaaS scale: store reads per 1000 requests
 * for staged tenant candidates, with and without one on the platform, at two request rates and several bounds and
 * absence lifetimes of the candidate cache. It is a `main` and nothing JUnit discovers, so `check` compiles it and
 * never runs it; it runs for minutes and takes gigabytes of heap, so run it by hand, niced, and never on a shared
 * machine's full load. In-memory stores stand in for Redis: it needs no Redis.
 *
 * Run: `nice -n 15 ./gradlew :runtime:testClasses`, then `java -Xmx4g -cp <the runtime module's test runtime
 * classpath> me.ahoo.cosec.runtime.shadow.SaasScaleCandidateMeasurementKt` (the optional argument is the file the
 * report is written to (default `candidate-load.txt`)). There is no Gradle task for it: the classpath is what an
 * IDE's run configuration for this file's `main` uses. Its numbers are in `docs/reviews/2026-11/`:
 * `saas-scale-data-plane.md` (the candidate-load table). They are of the commit that report names; this file is
 * kept compiling against `main`, not re-measured.
 */

private class StepClock : Clock() {
    var now: Instant = Instant.parse("2026-11-19T00:00:00Z")
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

private class CountingStore(private val staged: Map<SnapshotKey, StoredCandidate>) : CandidateStore {
    var reads = 0L
    var absent = 0L
    override suspend fun candidate(key: SnapshotKey): StoredCandidate? {
        reads++
        return staged[key].also { if (it == null) absent++ }
    }
}

private val lines = StringBuilder()

private fun run(windows: Int, onPlatform: Boolean, rate: Int, maxEntries: Int, refresh: Duration, tenants: Int = 10_000) {
    val document = SnapshotDocuments.read(
        """{"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z"}""".encodeToByteArray()
    )
    val staged = HashMap<SnapshotKey, StoredCandidate>()
    repeat(windows) { staged[SnapshotKey.Tenant("t-${it * 97 % tenants}")] = StoredCandidate("w-$it", 1, document) }
    if (onPlatform) staged[SnapshotKey.Platform] = StoredCandidate("w-platform", 1, document)
    val store = CountingStore(staged)
    val clock = StepClock()
    val compiler = PolicyDocumentCompiler()
    // The cache's bound is always given here; its default is now `CachingCandidateSource.DEFAULT_MAX_ENTRIES` (1,024) and
    // was another when this ran, so a run that needs the default of then passes it as `maxEntries`.
    val source = CachingCandidateSource(store, { key, doc -> compiler.compile(key, doc) }, maxEntries, refresh, clock)
    val random = Random(7)
    val step = Duration.ofNanos(1_000_000_000L / rate)
    var sampled = 0L
    fun request(count: Boolean) = runBlocking {
        clock.now = clock.now.plus(step)
        if (random.nextDouble() >= 0.1) return@runBlocking
        if (count) sampled++
        // What ShadowListener.documents reads for a request with a tenant and an app: the platform, the app, the tenant.
        source.candidate(SnapshotKey.Platform)
        source.candidate(SnapshotKey.App("app-${random.nextInt(12)}"))
        source.candidate(SnapshotKey.Tenant("t-${random.nextInt(tenants)}"))
    }
    repeat(100_000) { request(false) }
    val reads0 = store.reads
    val absent0 = store.absent
    val requests = 400_000
    repeat(requests) { request(true) }
    val per = 1000.0 / requests
    val line = "windows=$windows${if (onPlatform) "+platform" else ""} rate=$rate/s maxEntries=$maxEntries refresh=${refresh.seconds}s: " +
        "store reads per 1000 requests=${"%.1f".format((store.reads - reads0) * per)} " +
        "(for absent keys ${"%.1f".format((store.absent - absent0) * per)}), sampled=${"%.1f".format(sampled * per)}"
    println(line)
    lines.appendLine(line)
}

fun main(args: Array<String>) {
    val ten = Duration.ofSeconds(10)
    for (rate in intArrayOf(500, 5_000)) {
        run(0, false, rate, 16, ten)
        run(1, false, rate, 16, ten)
        run(100, false, rate, 16, ten)
        run(1, true, rate, 16, ten)
    }
    // The alternatives of (a): a larger bound, a longer absence lifetime.
    for (max in intArrayOf(1_024, 10_016)) for (refresh in longArrayOf(10, 60)) {
        run(0, false, 5_000, max, Duration.ofSeconds(refresh))
        run(0, false, 500, max, Duration.ofSeconds(refresh))
    }
    File(args.getOrElse(0) { "candidate-load.txt" }).writeText(lines.toString())
}
