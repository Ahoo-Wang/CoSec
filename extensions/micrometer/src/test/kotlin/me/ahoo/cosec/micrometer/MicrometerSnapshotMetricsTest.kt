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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.HousekeepingTask
import me.ahoo.cosec.runtime.snapshot.LoadOutcome
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.summary
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The `cosec.snapshot.*` meters (`OBS-5`): one per event, tagged by closed sets of values, resolved once, and the
 * gauges over a real source's summary.
 */
class MicrometerSnapshotMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerSnapshotMetrics(registry)

    private fun count(name: String, vararg tags: String): Double =
        registry.get(name).tags(*tags).meter().measure().single().value

    @ParameterizedTest
    @EnumSource(LoadOutcome::class, names = ["COLD", "FAILED", "TIMEOUT"])
    fun `a load is counted by kind and result`(outcome: LoadOutcome) {
        metrics.onLoad(SnapshotKind.APP, outcome)
        metrics.onLoad(SnapshotKind.APP, outcome)
        count(
            MicrometerSnapshotMetrics.LOADS,
            "kind",
            "app",
            "result",
            outcome.name.lowercase()
        ).assert().isEqualTo(2.0)
        count(
            MicrometerSnapshotMetrics.LOADS,
            "kind",
            "tenant",
            "result",
            outcome.name.lowercase()
        ).assert().isEqualTo(0.0)
    }

    /** A hit is not a load, and `peek` is every request's path: it is not metered at all (#1614). */
    @Test
    fun `a hit is not metered`() {
        val before = registry.meters.size
        repeat(3) { metrics.onLoad(SnapshotKind.APP, LoadOutcome.HIT) }
        registry.meters.size.assert().isEqualTo(before)
        registry.find(MicrometerSnapshotMetrics.LOADS).tag("result", "hit").meters().assert().isEmpty()
        val loads = registry.find(MicrometerSnapshotMetrics.LOADS).meters()
        loads.sumOf { it.measure().single().value }.assert().isEqualTo(0.0)
    }

    @Test
    fun `a compile is timed by kind and result`() {
        metrics.onCompile(SnapshotKind.TENANT, Duration.ofMillis(3), succeeded = true)
        metrics.onCompile(SnapshotKind.TENANT, Duration.ofMillis(5), succeeded = false)
        val timer = { result: String ->
            registry.get(MicrometerSnapshotMetrics.COMPILE_DURATION).tags("kind", "tenant", "result", result).timer()
        }
        timer("success").count().assert().isEqualTo(1L)
        timer("failure").count().assert().isEqualTo(1L)
    }

    @Test
    fun `regressions, lost documents, lag reports, evictions and refused hand-offs are counted`() {
        val tenant = SnapshotKey.Tenant("acme")
        metrics.onRegression(tenant, 5, 4)
        metrics.onDocumentLost(tenant, 5)
        metrics.onLagExceeded(tenant, Duration.ofMinutes(2), escalated = false)
        metrics.onLagExceeded(tenant, Duration.ofMinutes(10), escalated = true)
        metrics.onEvicted(SnapshotKind.APP, absent = false)
        metrics.onEvicted(SnapshotKind.APP, absent = true)
        metrics.onEvicted(SnapshotKind.APP, absent = true)
        metrics.onHandOffRefused(ranInPlace = true)
        metrics.onHandOffRefused(ranInPlace = false)
        count(MicrometerSnapshotMetrics.REGRESSIONS, "kind", "tenant").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.LOST, "kind", "tenant").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.LAG_EXCEEDED, "kind", "tenant", "level", "warn").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.LAG_EXCEEDED, "kind", "tenant", "level", "escalate").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.EVICTIONS, "kind", "app", "state", "document").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.EVICTIONS, "kind", "app", "state", "absent").assert().isEqualTo(2.0)
        count(MicrometerSnapshotMetrics.HANDOFFS_REFUSED, "to", "caller").assert().isEqualTo(1.0)
        count(MicrometerSnapshotMetrics.HANDOFFS_REFUSED, "to", "housekeeping").assert().isEqualTo(1.0)
    }

    /** One series per housekeeping task, its tag the task's own: a refusal counts under its task and no other. */
    @Test
    fun `refused housekeeping tasks are counted by task`() {
        metrics.onHousekeepingRefused(HousekeepingTask.LOAD_LOG)
        metrics.onHousekeepingRefused(HousekeepingTask.LOAD_LOG)
        metrics.onHousekeepingRefused(HousekeepingTask.DROP)
        count(MicrometerSnapshotMetrics.HOUSEKEEPING_REFUSED, "task", "load-log").assert().isEqualTo(2.0)
        count(MicrometerSnapshotMetrics.HOUSEKEEPING_REFUSED, "task", "drop").assert().isEqualTo(1.0)
        registry.find(MicrometerSnapshotMetrics.HOUSEKEEPING_REFUSED).counters().map { it.id.getTag("task") }.toSet()
            .assert().isEqualTo(
                setOf(
                    "load-log", "compile-log", "install-review", "effect-log", "crash-log", "transition-log",
                    "prewarm-log", "pass-log", "reset-log", "evict", "drop", "lag-report",
                ),
            )
    }

    /** Every meter exists from the constructor on, and no event adds one: the series are fixed whatever ids pass. */
    @Test
    fun `the series are fixed, no event adds a meter`() {
        val before = registry.meters.size
        before.assert().isEqualTo(LOAD_SERIES + COMPILE_SERIES + 3 + 6 + 3 + 3 + 6 + 2 + HousekeepingTask.entries.size)
        repeat(1_000) {
            val kind = SnapshotKind.entries[it % SnapshotKind.entries.size]
            metrics.onLoad(kind, LoadOutcome.entries[it % LoadOutcome.entries.size])
            metrics.onEvicted(kind, absent = it % 2 == 0)
            metrics.onLagExceeded(SnapshotKey.Tenant("tenant-$it"), Duration.ofMinutes(2), escalated = it % 3 == 0)
            metrics.onDocumentLost(SnapshotKey.App("app-$it"), it.toLong())
            metrics.onHousekeepingRefused(HousekeepingTask.entries[it % HousekeepingTask.entries.size])
        }
        registry.meters.size.assert().isEqualTo(before)
        registry.meters.flatMap { it.id.tags }.map { it.key }.toSet()
            .assert().isEqualTo(setOf("kind", "result", "level", "state", "to", "task"))
    }

    @Test
    fun `the gauges read the source's last lag pass, and its age`() {
        val store = InMemorySnapshotDocumentStore()
        store.put(SnapshotKey.Platform, document(1))
        val compiler = SnapshotCompiler { key, document -> key to document.revision }
        val now = Instant.parse("2026-11-18T00:00:00Z")
        val bound = MicrometerSnapshotMetrics(registry, Clock.fixed(now, ZoneOffset.UTC))
        DocumentSnapshots.source(
            store,
            compiler,
            SnapshotOptions(revalidateInterval = Duration.ofSeconds(1)),
            Clock.systemUTC(),
            bound,
        ).use { source ->
            bound.bind(source)
            val age = registry.get(MicrometerSnapshotMetrics.SUMMARY_AGE).gauge()
            source.load().join()
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (source.summary().takenAt == null) {
                check(System.nanoTime() < deadline) { "no lag pass ran" }
                Thread.sleep(50)
            }
            registry.get(
                MicrometerSnapshotMetrics.INSTALLED
            ).tags("kind", "platform", "state", "present").gauge().value()
                .assert().isEqualTo(1.0)
            registry.get(MicrometerSnapshotMetrics.INSTALLED).tags("kind", "app", "state", "absent").gauge().value()
                .assert().isEqualTo(0.0)
            registry.get(
                MicrometerSnapshotMetrics.LAG
            ).tags("kind", "platform").gauge().value().assert().isNotNegative()
            registry.get(MicrometerSnapshotMetrics.LAGGING).tags("kind", "platform", "level", "warn").gauge().value()
                .assert().isEqualTo(0.0)
            age.value().isNaN().assert().isFalse()
            count(MicrometerSnapshotMetrics.INSTALLS, "kind", "platform").assert().isEqualTo(1.0)
        }
    }

    private fun document(revision: Long) = SnapshotDocuments.read(
        """{"schema":2,"revision":$revision,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}"""
            .encodeToByteArray(),
    )

    private companion object {
        const val LOAD_SERIES = 9
        const val COMPILE_SERIES = 6
    }
}
