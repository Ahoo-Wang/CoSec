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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.File
import java.lang.management.ManagementFactory

/**
 * A measurement, not a check: what a shadow listener costs per request when no window is open: caller nanoseconds
 * and bytes per decision, and process CPU, with no listener, a no-op listener, and the shadow listener at 10 % and
 * 0 %. It is a `main` and nothing JUnit discovers, so `check` compiles it and never runs it; it runs for minutes
 * and takes gigabytes of heap, so run it by hand, niced, and never on a shared machine's full load. In-memory
 * stores stand in for Redis: it needs no Redis.
 *
 * Run: `nice -n 15 ./gradlew :runtime:testClasses`, then `java -Xmx4g -cp <the runtime module's test runtime
 * classpath> me.ahoo.cosec.runtime.SaasShadowCostMeasurementKt` (the optional argument is the file the report is
 * written to (default `shadow-cost.txt`)). There is no Gradle task for it: the classpath is what an IDE's run
 * configuration for this file's `main` uses. Its numbers are in `docs/reviews/2026-11/`:
 * `saas-shadow-staged-design.md` (Design D2), with the raw output at the end of `saas-scale-raw-output.txt`. They
 * are of the commit that report names; this file is kept compiling against `main`, not re-measured.
 */

private object NoOp : DecisionListener {
    override val name = "no-op"
    override suspend fun onDecision(event: DecisionEvent) = Unit
}

private val out = StringBuilder()

private fun measure(label: String, listeners: List<DecisionListener>) {
    val store = InMemorySnapshotDocumentStore()
    store.put(
        SnapshotKey.Platform,
        SnapshotDocuments.read(
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-11-20T00:00:00Z","policies":[{"id":"public","statements":""" +
                    """[{"effect":"allow","action":"http:GET","resource":"http:path:/public/**"}]}],""" +
                    """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"}]}"""
                ).encodeToByteArray()
        ),
    )
    val facts = HttpFacts("GET", "/public/x", null, Headers.EMPTY, Cookies.EMPTY, "10.0.0.1", HostScope(null))
    val builder = Pipeline.builder(Authenticator { AuthnResult.Anonymous }, store)
    listeners.forEach { builder.listener(it) }
    builder.build().use { pipeline ->
        runBlocking {
            pipeline.awaitDocuments()
            check(pipeline.evaluate(facts).proceed)
            repeat(WARM) { pipeline.evaluate(facts) }
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val id = Thread.currentThread().threadId()
            val bytes0 = threads.getThreadAllocatedBytes(id)
            val cpu0 = os.processCpuTime
            val wall0 = System.nanoTime()
            repeat(N) { pipeline.evaluate(facts) }
            val wall = (System.nanoTime() - wall0).toDouble() / N
            val bytes = (threads.getThreadAllocatedBytes(id) - bytes0).toDouble() / N
            // Let the listeners' consumers drain what was offered before reading the process's CPU.
            Thread.sleep(DRAIN_MILLIS)
            val cpu = (os.processCpuTime - cpu0).toDouble() / N
            val line = "%-34s caller %.0f ns/op, %.0f B/op on the caller; process CPU %.0f ns/op".format(
                label,
                wall,
                bytes,
                cpu
            )
            println(line)
            out.appendLine(line)
        }
    }
}

private const val WARM = 200_000
private const val N = 400_000
private const val DRAIN_MILLIS = 500L

fun main(args: Array<String>) {
    val compiler = PolicyDocumentCompiler()
    val none = CandidateStore { null }
    fun shadow(rate: Double) = ShadowListener(
        "shadow",
        rate,
        CachingCandidateSource(none, { key, document -> compiler.compile(key, document) }),
        ShadowRecorder("i-1"),
    )
    repeat(2) { round ->
        out.appendLine("round $round")
        measure("no listener", emptyList())
        measure("metrics stand-in (no-op)", listOf(NoOp))
        measure("no-op + shadow at 10 %", listOf(NoOp, shadow(0.1)))
        measure("no-op + shadow at 0 %", listOf(NoOp, shadow(0.0)))
        measure("shadow at 10 % alone", listOf(shadow(0.1)))
    }
    File(args.getOrElse(0) { "shadow-cost.txt" }).writeText(out.toString())
}
