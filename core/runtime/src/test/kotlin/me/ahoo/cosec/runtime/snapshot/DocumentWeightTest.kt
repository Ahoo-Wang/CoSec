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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import javax.management.ObjectName

/**
 * `SNP-24`: [DocumentWeight] against what a resident document retains in a live [SnapshotSource]: its slot, its entry
 * and its revision kept, measured as the live heap a class histogram counts after a full collection, before and after N
 * documents are loaded into one source whose store already holds them, once a first N warmed the code. What the source
 * shares with every document, and what the JVM holds besides, cancels out (a graph walk from a slot reaches the source's
 * threads and, through them, classes a busy JVM keeps loading: `HeldKeyBytesTest` measures a held key this way too).
 * Twelve shapes: tenants of roles only, of many role or principal bindings, of explicit policies with and without conditions, the deployment page's typical tenant and the
 * largest the compiler admits, and apps of small to large catalogs. The estimate is at least [AT_LEAST] times what was
 * measured, a margin over a measure that moves a few percent from run to run, and at most [AT_MOST] times it; and the stored size alone could not do that, which is why it is not the estimate.
 */
class DocumentWeightTest {
    private val published = "2026-11-19T00:00:00Z"
    private val apps = 12

    private fun app(app: Int, groups: Int, perGroup: Int, roles: Int): String {
        val g = (0 until groups).joinToString(",") { gi ->
            val permissions = (0 until perGroup).joinToString(",") { i ->
                val resource = if (i % 4 == 0) ""","resource":"res-$i/{id}"""" else ""
                """{"id":"g$gi.p$i","name":"Permission $gi.$i of app $app","action":"app-$app:g$gi.p$i"$resource}"""
            }
            """{"name":"Group $gi","permissions":[$permissions]}"""
        }
        val total = groups * perGroup
        val r = (0 until roles).joinToString(",") { ri ->
            val permissions = if (ri == 0) {
                "\"*\""
            } else {
                (0 until minOf(total, ri * 9)).joinToString(",", "[", "]") {
                    "\"g${it / perGroup}.p${it % perGroup}\""
                }
            }
            """{"id":"role-$ri","name":"Role $ri","description":"System role $ri of app $app","permissions":$permissions}"""
        }
        return """{"schema":2,"revision":1,"publishedAt":"$published","catalog":{"groups":[$g]},"roles":[$r]}"""
    }

    private fun tenant(t: Int, roles: Int, perms: Int, bindings: Int, principals: Int = 0, policies: Int = 0, statements: Int = 0, conditions: Boolean = false): String {
        val r = (0 until roles).joinToString(",") { k ->
            val permissions = (0 until perms).joinToString(",", "[", "]") { j ->
                val p = (t * 7 + k * 20 + j) % 100
                "\"g${p / 20}.p${p % 20}\""
            }
            """{"id":"custom-$t-$k","app":"app-${(t + k) % apps}","name":"Custom role $k of tenant $t","description":"A custom role","permissions":$permissions}"""
        }
        val p = (0 until policies).joinToString(",") { pi ->
            val st = (0 until statements).joinToString(",") { si ->
                val cond = if (conditions) ""","condition":{"not":{"cidr":["environment.ip",["10.$pi.$si.0/24"]]}}""" else ""
                """{"name":"s$si","effect":"${if (si % 3 == 0) "deny" else "allow"}","action":"http:${if (si % 2 == 0) "GET" else "POST"}","resource":"http:path:/t$t/p$pi/s$si/**"$cond}"""
            }
            """{"id":"pol-$t-$pi","statements":[$st]}"""
        }
        val b = (0 until bindings).map { j ->
            val a = (t + j) % apps
            val q = (t * 3 + j * 5) % 100
            """{"id":"b-$t-$j","subject":{"role":"role-${j % 12}"},"app":"app-$a","space":"*","policy":"perm:app-$a/g${q / 20}.p${q % 20}"}"""
        } + (0 until principals).map { j ->
            val a = (t + j) % apps
            """{"id":"u-$t-$j","subject":{"principal":"user-$t-$j"},"app":"app-$a","space":"*","policy":"perm:app-$a/g${j % 5}.p${j % 20}"}"""
        } + (0 until policies).map { pi -> """{"id":"pb-$t-$pi","subject":"*","app":"*","space":"*","policy":"pol-$t-$pi"}""" }
        val parts = listOfNotNull(
            if (roles > 0) "\"roles\":[$r]" else null,
            if (policies > 0) "\"policies\":[$p]" else null,
            if (b.isNotEmpty()) "\"bindings\":[${b.joinToString(",")}]" else null,
        )
        return """{"schema":2,"revision":1,"publishedAt":"$published"${parts.joinToString("") { ",$it" }}}"""
    }

    private fun doc(json: String) = SnapshotDocuments.read(json.encodeToByteArray())

    /** Live bytes on the heap, summed over every class, after the full collection a class histogram makes. */
    private fun liveBytes(): Long {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf(Array<String>::class.java.name),
        ) as String
        return text.lines().sumOf { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4 && parts[0].endsWith(":")) parts[2].toLong() else 0L
        }
    }

    /**
     * What one resident document of [document]'s shape retains in [kind]'s family: the live heap after [n] of them are
     * loaded into a source, less before, over [n]. The store holds them before the first count, and a first run warms the
     * classes and the code, so what the source shares with every document, and what the JVM holds besides, cancels out.
     */
    private fun held(kind: SnapshotKind, n: Int, document: (Int) -> String): Long {
        val store = InMemorySnapshotDocumentStore()
        store.put(SnapshotKey.Platform, doc(platform()))
        repeat(apps) { store.put(SnapshotKey.App("app-$it"), doc(app(it, 5, 20, 12))) }
        val keys = (0 until 2 * n).map {
            if (kind == SnapshotKind.TENANT) SnapshotKey.Tenant("t-$it") else SnapshotKey.App("x-$it")
        }
        keys.forEachIndexed { i, key -> store.put(key, doc(document(i))) }
        val compiler = PolicyDocumentCompiler()
        val options = SnapshotOptions(
            revalidateInterval = Duration.ofMinutes(4),
            lagWarn = Duration.ofMinutes(5),
            lagEscalate = Duration.ofMinutes(10),
            maxApps = 1_000,
            maxTenants = 1_000,
        )
        return SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options,
            Clock.systemUTC(), SnapshotMetrics.NOOP, null, null, System::nanoTime, null,
        ).use { source ->
            source.load().join()
            // The first half warms what loading a document touches the first time; the second half is measured.
            keys.take(n).forEach { source.loadAll(listOf(it)).join() }
            val before = liveBytes()
            keys.drop(n).forEach { source.loadAll(listOf(it)).join() }
            val after = liveBytes()
            check(source.entries().size == 1 + 2 * n) { "Every document is resident." }
            (after - before) / n
        }
    }

    private fun platform(): String =
        File("../../wiki/examples/app/documents/platform.json").takeIf { it.exists() }?.readText()
            ?: File("wiki/examples/app/documents/platform.json").readText()

    private class Shape(val name: String, val kind: SnapshotKind, val held: Int, val document: (Int) -> String)

    private val shapes = listOf(
        Shape("small tenant", SnapshotKind.TENANT, 40) { tenant(it, 1, 5, 2) },
        Shape("typical tenant", SnapshotKind.TENANT, 40) { tenant(it, 5, 20, 20) },
        Shape("large tenant", SnapshotKind.TENANT, 20) { tenant(it, 20, 40, 100) },
        Shape("tenant of roles only", SnapshotKind.TENANT, 30) { tenant(it, 12, 30, 0) },
        Shape("tenant of many bindings", SnapshotKind.TENANT, 20) { tenant(it, 2, 10, 300) },
        Shape("tenant of principal bindings", SnapshotKind.TENANT, 10) { tenant(it, 5, 20, 20, principals = 1000) },
        Shape("tenant of policies with conditions", SnapshotKind.TENANT, 20) {
            tenant(it, 0, 0, 0, policies = 30, statements = 4, conditions = true)
        },
        Shape(
            "tenant of plain policies",
            SnapshotKind.TENANT,
            20
        ) { tenant(it, 0, 0, 0, policies = 60, statements = 6) },
        Shape("largest tenant", SnapshotKind.TENANT, 8) { tenant(it, 40, 50, 200, principals = 1000) },
        Shape("small app", SnapshotKind.APP, 20) { app(it, 1, 10, 3) },
        Shape("the harness's app", SnapshotKind.APP, 12) { app(it, 5, 20, 12) },
        Shape("large app", SnapshotKind.APP, 8) { app(it, 15, 20, 30) },
    )

    @Test
    fun `the estimate is above what a resident document retains, and at most 1·6 times it`() {
        val compiler = PolicyDocumentCompiler()
        val storedOnly = ArrayList<Double>()
        val results = ArrayList<Triple<String, Long, Long>>()
        for (shape in shapes) {
            val json = shape.document(0)
            val key = if (shape.kind == SnapshotKind.TENANT) SnapshotKey.Tenant("t-0") else SnapshotKey.App("x-0")
            val compiled = compiler.compile(key, doc(json)) as CompiledDocument
            val estimate = DocumentWeight.of(compiled, json.length)
            val measured = held(shape.kind, shape.held, shape.document)
            storedOnly += measured.toDouble() / json.length
            println(
                "DocumentWeight ${shape.name}: stored ${json.length} B, ${compiled.bindingCount} bindings, " +
                    "${compiled.statementCount} statements; retains $measured B, estimated $estimate B"
            )
            results += Triple(shape.name, estimate, measured)
        }
        for ((name, estimate, measured) in results) {
            (estimate.toDouble() / measured).assert().describedAs(name).isGreaterThanOrEqualTo(AT_LEAST)
            (estimate.toDouble() / measured).assert().describedAs(name).isLessThanOrEqualTo(AT_MOST)
        }
        // One factor of the stored size could not hold every shape within the band the estimate holds.
        (storedOnly.max() / storedOnly.min()).assert().isGreaterThan(AT_MOST)
    }

    @Test
    fun `a document that did not compile weighs what the slot keeps of it, and less than one that did`() {
        val json = shapes.first().document(0)
        val compiled = PolicyDocumentCompiler().compile(SnapshotKey.Tenant("t-0"), doc(json)) as CompiledDocument
        DocumentWeight.ofUncompiled(json.length).assert()
            .isEqualTo(DocumentWeight.FIXED_BYTES + json.length * DocumentWeight.STORED_HALF_BYTES / 2)
            .isLessThan(DocumentWeight.of(compiled, json.length))
    }

    private companion object {
        const val AT_LEAST = 1.03
        const val AT_MOST = 1.6
    }
}
