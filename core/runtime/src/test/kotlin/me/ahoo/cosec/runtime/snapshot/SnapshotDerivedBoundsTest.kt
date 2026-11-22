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
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `SNP-24`: the families' bounds derived from the documents' share of the heap ([DocumentBudget]), on real documents and
 * a real compiler. The bound follows what is resident, recomputed by a pass; the absent entries' part is fixed, so ids
 * nobody owns never shrink what the documents get; a document heavier than the whole share leaves a family at its floor,
 * never empty; one very heavy tenant among many small ones lowers the others' bound by its own weight, no more; and a
 * small pod's budget still turns ready.
 */
class SnapshotDerivedBoundsTest {
    private val published = "2026-11-19T00:00:00Z"

    private fun doc(json: String) = SnapshotDocuments.read(json.encodeToByteArray())

    /** A tenant of [roles] roles of [perms] permissions and [bindings] role bindings, as `DocumentWeightTest`'s. */
    private fun tenant(t: Int, roles: Int, perms: Int, bindings: Int): String {
        val r = (0 until roles).joinToString(",") { k ->
            val permissions = (0 until perms).joinToString(",", "[", "]") { j ->
                val p = (t * 7 + k * 20 + j) % 100
                "\"g${p / 20}.p${p % 20}\""
            }
            """{"id":"custom-$t-$k","app":"app-${(t + k) % APPS}","name":"Custom role $k","permissions":$permissions}"""
        }
        val b = (0 until bindings).joinToString(",") { j ->
            val a = (t + j) % APPS
            val q = (t * 3 + j * 5) % 100
            """{"id":"b-$t-$j","subject":{"role":"role-${j % 12}"},"app":"app-$a","space":"*",""" +
                """"policy":"perm:app-$a/g${q / 20}.p${q % 20}"}"""
        }
        return """{"schema":2,"revision":1,"publishedAt":"$published","roles":[$r],"bindings":[$b]}"""
    }

    private fun app(a: Int): String {
        val groups = (0 until 5).joinToString(",") { g ->
            val permissions = (0 until 20).joinToString(",") { i ->
                """{"id":"g$g.p$i","name":"Permission $g.$i","action":"app-$a:g$g.p$i"}"""
            }
            """{"name":"Group $g","permissions":[$permissions]}"""
        }
        val roles = (0 until 12).joinToString(",") { r ->
            val permissions = (0 until minOf(100, r * 9)).joinToString(",", "[", "]") { "\"g${it / 20}.p${it % 20}\"" }
            """{"id":"role-$r","name":"Role $r","permissions":$permissions}"""
        }
        return """{"schema":2,"revision":1,"publishedAt":"$published","catalog":{"groups":[$groups]},"roles":[$roles]}"""
    }

    private fun small(t: Int) = tenant(t, 1, 5, 2)

    /** A store of the platform, the twelve apps of the target and [tenants] tenant documents. */
    private fun store(tenants: Map<Int, String>) = InMemorySnapshotDocumentStore().apply {
        put(SnapshotKey.Platform, doc("""{"schema":2,"revision":1,"publishedAt":"$published"}"""))
        repeat(APPS) { put(SnapshotKey.App("app-$it"), doc(app(it))) }
        tenants.forEach { (t, json) -> put(SnapshotKey.Tenant("t-$t"), doc(json)) }
    }

    private fun source(store: InMemorySnapshotDocumentStore, budget: Long) = DocumentSnapshots.source(
        store,
        PolicyDocumentCompiler(),
        SnapshotOptions(
            revalidateInterval = Duration.ofMinutes(4),
            lagWarn = Duration.ofMinutes(5),
            lagEscalate = Duration.ofMinutes(10),
            documentsBudget = budget
        ),
    )

    private fun SnapshotSource<*, *>.loadEach(keys: List<SnapshotKey>) = keys.forEach { loadAll(listOf(it)).join() }

    private fun tenants(ids: Iterable<Int>) = ids.map { SnapshotKey.Tenant("t-$it") }

    private fun held(source: SnapshotSource<*, *>) = source.heldCounts(SnapshotKind.TENANT).first

    private fun weightOf(t: Int, json: String): Long =
        DocumentWeight.of(
            PolicyDocumentCompiler().compile(SnapshotKey.Tenant("t-$t"), doc(json)) as CompiledDocument,
            json.length
        )

    private fun apps() = (0 until APPS).map { SnapshotKey.App("app-$it") }

    @Test
    fun `the budget's parts at the production overlay's heap and a small pod's`() {
        val gateway = DocumentBudget(HeapBudget.of(TWO_GIB).documents)
        gateway.total.assert().isEqualTo(512L shl 20)
        gateway.absentShare.assert().isEqualTo(32L shl 20)
        gateway.absentTenants.assert().isEqualTo(65_280)
        gateway.absentApps.assert().isEqualTo(256)
        gateway.firstBound(SnapshotKind.TENANT).assert().isEqualTo(14_566)
        val pod = DocumentBudget(HeapBudget.of(TWO_GIB / 4).documents)
        pod.absentTenants.assert().isEqualTo(16_320)
        pod.firstBound(SnapshotKind.TENANT).assert().isEqualTo(3_641)
        // A family keeps documents however heavy they are.
        pod.bound(
            SnapshotKind.TENANT,
            count = 1,
            weight = Long.MAX_VALUE / 4,
            typicalWeight = Long.MAX_VALUE / 4,
            others = 0
        ).assert().isEqualTo(DocumentBudget.FLOOR)
    }

    @Test
    fun `the bound follows what is resident, and housekeeping takes a family back to it`() {
        val heavy = (0 until 300).associateWith { tenant(it, 20, 40, 100) }
        // Room for about 200 of these heavy tenants beside the apps; the first estimate, at the typical weight, allows more.
        val budget = 64L shl 20
        source(store(heavy), budget).use { source ->
            source.load().join()
            source.loadEach(apps())
            val first = source.bound(SnapshotKind.TENANT, absent = false)
            source.loadEach(heavy.keys.map { SnapshotKey.Tenant("t-$it") })
            source.tick().join()
            val derived = source.bound(SnapshotKind.TENANT, absent = false)
            derived.assert().isLessThan(first)
            await { source.heldCounts(SnapshotKind.TENANT).first <= derived }
            val resident = source.residentWeight(SnapshotKind.TENANT) + source.residentWeight(SnapshotKind.APP)
            resident.assert().isLessThanOrEqualTo(DocumentBudget(budget).resident + derivedSlack(source))
        }
    }

    /** One mean document's weight of slack: the bound is a count, so the weight held lands within one document of it. */
    private fun derivedSlack(source: SnapshotSource<*, *>): Long {
        val (count, _) = source.heldCounts(SnapshotKind.TENANT)
        return source.residentWeight(SnapshotKind.TENANT) / count.coerceAtLeast(1)
    }

    @Test
    fun `ids nobody owns never shrink what the documents get`() {
        val tenants = (0 until 200).associateWith { small(it) }
        source(store(tenants), 64L shl 20).use { source ->
            source.load().join()
            source.loadEach(apps() + tenants.keys.map { SnapshotKey.Tenant("t-$it") })
            source.tick().join()
            val before = source.bound(SnapshotKind.TENANT, absent = false)
            source.loadEach((0 until 5_000).map { SnapshotKey.Tenant("nobody-$it") })
            source.tick().join()
            source.bound(SnapshotKind.TENANT, absent = false).assert().isEqualTo(before)
            source.heldCounts(SnapshotKind.TENANT).second.assert().isEqualTo(5_000)
        }
    }

    @Test
    fun `a document heavier than the whole share leaves its family at the floor, and is served`() {
        val tenants = mapOf(0 to tenant(0, 40, 50, 200)) + (1..100).associateWith { small(it) }
        // A share smaller than that one document.
        source(store(tenants), 64L shl 10).use { source ->
            source.load().join()
            source.loadEach(tenants.keys.map { SnapshotKey.Tenant("t-$it") })
            source.tick().join()
            source.bound(SnapshotKind.TENANT, absent = false).assert().isEqualTo(DocumentBudget.FLOOR)
            source.loadAll(listOf(SnapshotKey.Tenant("t-0"))).join().entry(SnapshotKey.Tenant("t-0"))!!.state
                .assert().isInstanceOf(DocumentState.Present::class.java)
        }
    }

    /**
     * A pass recomputes the bounds even when nothing is evicted, so `cosec.snapshot.bound` follows what is resident: a
     * thousand small tenants, fewer than the first estimate, are held at the first estimate until a pass.
     */
    @Test
    fun `each pass recomputes the bound from what is resident, with nothing to evict`() {
        val smalls = (1..1_000).associateWith { small(it) }
        val budget = 128L shl 20
        source(store(smalls), budget).use { source ->
            source.load().join()
            source.loadEach(apps() + tenants(smalls.keys))
            held(source).assert().isEqualTo(1_000)
            val first = DocumentBudget(budget).firstBound(SnapshotKind.TENANT)
            source.bound(SnapshotKind.TENANT, absent = false).assert().isEqualTo(first)
            source.tick().join()
            source.bound(SnapshotKind.TENANT, absent = false).assert().isGreaterThan(first)
        }
    }

    /**
     * The target's shape, a dozen apps, 10,000 small tenants and one very large one, loaded cold. The first estimate
     * (3,641 tenants of the deployment page's weight in this share) and a pass that runs while the large tenant is alone
     * (whose mean is its own weight: 214) are both far below 10,000; neither evicts anything, since an eviction pass
     * recomputes the bound first and the share has room. Once all are resident, the large tenant takes its own weight
     * in documents of the others from their bound, no more: the mean leaves it out.
     */
    @Test
    fun `a cold start evicts nothing while the share has room, and one very large tenant costs the others its own weight`() {
        val smalls = (1..10_000).associateWith { small(it) }
        val budget = 128L shl 20
        val without = source(store(smalls), budget).use { source ->
            source.load().join()
            source.loadEach(apps() + tenants(smalls.keys))
            held(source).assert().describedAs("held right after the cold loads").isEqualTo(10_000)
            source.tick().join()
            held(source).assert().isEqualTo(10_000)
            source.bound(SnapshotKind.TENANT, absent = false) to source.residentWeight(SnapshotKind.TENANT) / 10_000
        }
        val large = tenant(0, 40, 50, 200)
        source(store(smalls + (0 to large)), budget).use { source ->
            source.load().join()
            source.loadEach(apps() + tenants(listOf(0)))
            source.tick().join()
            val alone = source.bound(SnapshotKind.TENANT, absent = false)
            alone.assert().describedAs("the bound of a pass that finds the large tenant alone").isLessThan(1_000)
            source.loadEach(tenants(smalls.keys))
            held(source).assert().describedAs("held right after the cold loads").isEqualTo(10_001)
            source.tick().join()
            held(source).assert().isEqualTo(10_001)
            val with = source.bound(SnapshotKind.TENANT, absent = false)
            val itsOwnWeight = weightOf(0, large) / without.second + 1
            println(
                "SNP-24 noisy neighbour: bound ${without.first} without the large tenant, $with with it " +
                    "(its own weight ${itsOwnWeight - 1} typical documents); $alone while it was alone"
            )
            with.assert().isLessThan(without.first).isGreaterThanOrEqualTo((without.first - itsOwnWeight).toInt())
        }
    }

    /**
     * The worst one very large tenant does to the others: arriving into a share already full of small tenants, it evicts
     * what its own weight makes room for, in documents of the typical weight (the trimmed mean, which leaves it out), and
     * no more; and it is held.
     */
    @Test
    fun `one very large tenant arriving into a full share evicts at most its own weight in documents of the others`() {
        val smalls = (1..10_000).associateWith { small(it) }
        val large = tenant(0, 40, 50, 200)
        // Room for about 7,500 of the small tenants beside the apps.
        source(store(smalls + (0 to large)), 32L shl 20).use { source ->
            source.load().join()
            source.loadEach(apps() + tenants(smalls.keys))
            source.evictionPass(SnapshotKind.TENANT, Int.MAX_VALUE)
            val before = held(source)
            before.assert().isLessThan(10_000)
            val typical = source.residentWeight(SnapshotKind.TENANT) / before
            source.loadEach(tenants(listOf(0)))
            source.evictionPass(SnapshotKind.TENANT, Int.MAX_VALUE)
            val evicted = before + 1 - held(source)
            val atMost = (weightOf(0, large) + typical - 1) / typical
            println("SNP-24 a full share: $before small tenants held; the large one evicted $evicted (at most $atMost)")
            evicted.toLong().assert().isGreaterThan(0).isLessThanOrEqualTo(atMost)
            source.loadAll(tenants(listOf(0))).join().entry(SnapshotKey.Tenant("t-0"))!!.state
                .assert().isInstanceOf(DocumentState.Present::class.java)
        }
    }

    @Test
    fun `a small pod's budget turns ready`() {
        val tenants = (0 until 64).associateWith { small(it) }
        val keys = tenants.keys.map { SnapshotKey.Tenant("t-$it") }
        source(store(tenants), HeapBudget.of(TWO_GIB / 4).documents).use { source ->
            val readiness = DocumentReadiness(source, keys)
            readiness.start()
            await { readiness.missing().isEmpty() }
        }
    }

    private companion object {
        const val APPS = 12
        const val TWO_GIB = 2L shl 30
    }
}
