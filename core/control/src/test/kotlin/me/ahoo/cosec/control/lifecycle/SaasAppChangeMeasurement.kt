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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeEventJson
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.grant.Dimension
import me.ahoo.cosec.control.grant.GrantAnalyzer
import me.ahoo.cosec.control.grant.GrantChange
import me.ahoo.cosec.control.grant.GrantDocuments
import me.ahoo.cosec.control.grant.GrantEquality
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.Grantor
import me.ahoo.cosec.control.grant.GrantorRights
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.ReferrerIndex
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.RevisionWrites
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.Catalog
import me.ahoo.cosec.document.CatalogGroup
import me.ahoo.cosec.document.CatalogPermission
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * G3 (R2C-14, R2C-25, R2C-26): what an app change that widens a permission tenants' roles list costs at the SaaS
 * target (10,000 and 50,000 tenants, a dozen apps of a dozen roles), and what the grant analysis costs as one policy or
 * the grantor's rights grow. The tables and what they settled are in `docs/reviews/2026-11/` (`saas-app-change-cost`).
 *
 * `check` compiles it and never runs it: it is gated, runs for minutes and needs a 4 GB test heap, above the build's
 * default. Run it by hand, niced, one test at a time, with a heap line (`tasks.test { maxHeapSize = "4g" }`) added to
 * `core/control/build.gradle.kts` for the run and not committed:
 * `COSEC_MEASURE_BUDGETS=true nice -n 15 ./gradlew --no-daemon --max-workers=2 :control:test --tests
 * '*SaasAppChangeMeasurement.bigPolicy'` (or `appChangeWidening`, `grantorRights`, `largestLegitimate`); the tables are
 * in the test's standard output (`-i`, or the result file).
 */
@EnabledIfEnvironmentVariable(named = "COSEC_MEASURE_BUDGETS", matches = "true")
class SaasAppChangeMeasurement {
    /** Counts what reaches the store, by operation, with the payload sizes CoCache would send. */
    class CountingStore(private val inner: ControlStore) : ControlStore by inner {
        val ops = ConcurrentHashMap<String, AtomicLong>()
        val bytes = ConcurrentHashMap<String, AtomicLong>()

        fun reset() {
            ops.clear()
            bytes.clear()
        }

        private fun count(op: String, size: Long) {
            ops.getOrPut(op) { AtomicLong() }.incrementAndGet()
            bytes.getOrPut(op) { AtomicLong() }.addAndGet(size)
        }

        fun summary(): String = ops.keys.sorted().joinToString("; ") { op ->
            "$op ×${ops.getValue(op).get()} (${bytes.getValue(op).get() / 1024} KB)"
        }

        override suspend fun head(key: SnapshotKey): StoredDocument? = inner.head(key)
            .also { count("GET head ${key.kind.name.lowercase()}", it?.document?.bodyBytes()?.size?.toLong() ?: 0) }

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument) =
            inner.putHead(key, expected, document).also { count("PUT head", document.bodyBytes().size.toLong()) }

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest =
            inner.putRecord(key, document).also { count("PUT record", document.bodyBytes().size.toLong()) }

        override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? =
            inner.record(key, digest).also { count("GET record", it?.bodyBytes()?.size?.toLong() ?: 0) }

        override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? =
            inner.index(key).also { count("GET index", 0) }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean =
            inner.putIndex(key, expected, index).also { count("PUT index", 0) }

        override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> =
            inner.referrers(target, policy).also { set ->
                // SMEMBERS: `<docKey>@<revision>[#own]` per member.
                count("SMEMBERS refs", set.sumOf { it.referrer.toString().length + 3L })
            }

        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
            inner.change(
                id
            ).also { count("GET change", it?.value?.let(ChangeRequestCodec::encode)?.size?.toLong() ?: 0) }

        override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean =
            inner.putChange(
                expected,
                change
            ).also { count("PUT change", ChangeRequestCodec.encode(change).size.toLong()) }

        override suspend fun openChanges(document: SnapshotKey): List<me.ahoo.cosec.control.store.ListedChange> =
            inner.openChanges(document).also { count("SMEMBERS open changes", 0) }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-20T00:00:00Z")
        val APPS = (0 until 12).map { AppId("a$it") }
        val BASE_ACCESS = PolicyId("base-access")

        val threads = ManagementFactory.getThreadMXBean()

        fun cpuMs(block: () -> Unit): Long {
            val start = threads.currentThreadCpuTime
            block()
            return (threads.currentThreadCpuTime - start) / 1_000_000
        }

        fun <T> wall(block: () -> T): Pair<T, Long> {
            val start = System.nanoTime()
            val value = block()
            return value to (System.nanoTime() - start) / 1_000_000
        }
    }

    // ---- R2C-14: the world of saas-scale-control-plane.md ----

    private fun allow(id: String, action: String) =
        PolicyDefinition(
            PolicyId(id),
            false,
            null,
            listOf(StatementSource("", Effect.ALLOW, listOf(action), emptyList()))
        )

    private fun control(id: String, subject: SubjectRef, policy: PolicyId) = Binding(
        BindingId(id),
        subject,
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private fun platform() = PolicyDocument(
        DocumentKind.PLATFORM,
        "",
        1,
        NOW,
        listOf(allow(BASE_ACCESS.value, "base:read")),
        listOf(
            control(
                "plato",
                SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                BuiltinPolicies.PLATFORM_ADMIN
            ),
            control("pia", SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("pia")), BuiltinPolicies.PLATFORM_ADMIN),
        ),
    )

    private fun permissions(app: AppId, widened: Boolean = false) = (0 until 100).map {
        val actions = listOf("${app.value}:op$it") + if (widened && it == 1) listOf("${app.value}:op1-export") else emptyList()
        CatalogPermission("p$it", actions = actions)
    }

    private fun app(app: AppId) = PolicyDocument(
        DocumentKind.APP,
        app.value,
        1,
        NOW,
        emptyList(),
        emptyList(),
        Catalog(listOf(CatalogGroup("all", permissions = permissions(app)))),
        roles = (0 until 12).map { r ->
            RoleDefinition(
                RoleId("sys$r"),
                RolePermissions.Listed(
                    (0 until 10).map {
                        "p${(r * 7 + it) % 100}"
                    }
                )
            )
        },
    )

    /** 5 custom roles × the same 20 permissions of app a0, and 20 role bindings: 6 bindings to `perm:a0/p1` each. */
    private val customRoles = (0 until 5).map { r ->
        RoleDefinition(RoleId("custom$r"), RolePermissions.Listed((0 until 20).map { "p$it" }), app = AppId("a0"))
    }
    private val roleBindings = listOf(
        Binding(BindingId("b0"), SubjectRef.Role(null, RoleId("member")), BindingScope(Match.Any, Match.Any), BASE_ACCESS),
    ) + (1 until 20).map { k ->
        Binding(BindingId("b$k"), SubjectRef.Role(null, RoleId("r$k")), BindingScope(Match.Exactly(AppId("a0")), Match.Any), PolicyId("perm:a0/p$k"))
    }

    private fun tenantId(i: Int) = "t%05d".format(i)

    private fun tenant(id: String) = PolicyDocument(
        DocumentKind.TENANT,
        id,
        1,
        NOW,
        emptyList(),
        roleBindings,
        roles = customRoles
    )

    private class World(val counting: CountingStore, val revisions: Revisions, val clock: MutableClock)

    private fun world(tenants: Int): World = runBlocking {
        val clock = MutableClock(NOW)
        val memory = InMemoryControlStore(clock)
        val counting = CountingStore(memory)
        val revisions = Revisions(counting, clock)
        val publication = Publication("seed", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato")))
        check(revisions.publish(SnapshotKey.Platform, null, platform(), publication) is PublishOutcome.Published)
        for (app in APPS) {
            check(
                revisions.publish(SnapshotKey.App(app.value), null, app(app), publication) is PublishOutcome.Published
            )
        }
        val index = ReferrerIndex(memory, RevisionWrites(memory, 16)) { null }
        val references = index.references(tenant(tenantId(0)))
        for (i in 0 until tenants) {
            val key = SnapshotKey.Tenant(tenantId(i))
            val stored = SnapshotDocuments.of(tenant(tenantId(i)))
            val digest = memory.putRecord(key, stored)
            memory.putIndex(key, null, RevisionIndex(listOf(RevisionEntry(1, digest, NOW))))
            memory.putHead(key, null, stored)
            for (reference in references) {
                memory.addReferrer(reference.target, reference.policy, ReferrerEntry(key, 1, reference.definesItself))
            }
        }
        counting.reset()
        World(counting, revisions, clock)
    }

    private class Recorder : ChangeListener {
        override val name = "measure"
        val events = CopyOnWriteArrayList<ChangeEvent>()
        override suspend fun onChange(event: ChangeEvent) {
            events += event
        }
    }

    private fun stepRow(n: Int, step: String, change: ChangeRequest, ms: Long, counting: CountingStore): String {
        val record = ChangeRequestCodec.encode(change).size
        val informational = change.escalations.count { it.informational }
        return "| $n | $step | ${change.state} ${change.findings.lastOrNull()?.toString()?.take(200) ?: ""} | $ms ms | ${change.escalations.size} ($informational informational) | " +
            "${change.checkedWith.size} | ${record / 1024} KB | ${counting.summary()} |"
    }

    // stays: a measurement prints one table from start to end, which reads best as one function.
    @Suppress("LongMethod")
    @Test
    fun appChangeWidening() {
        println(
            "### R2C-14: app a0 widens perm:a0/p1 (one action more), which every tenant's 5 custom roles and one role binding list\n"
        )
        println(
            "| tenants | step | state after | wall, in memory | escalations | checkedWith entries | change record | store traffic |"
        )
        println("|---|---|---|---|---|---|---|---|")
        for (n in listOf(1_000, 10_000, 50_000)) {
            val world = world(n)
            val recorder = Recorder()
            // The control plane's own authorizer over the heads: plato and pia are the platform's administrators.
            val engine = me.ahoo.cosec.control.authz.EngineAuthority(world.revisions)
            val plato = engine.operator(TenantId.PLATFORM, "plato")
            val pia = engine.operator(TenantId.PLATFORM, "pia")
            val ids = AtomicInteger()
            val changes = Changes(
                world.counting,
                world.revisions,
                { ChangeId("c${ids.incrementAndGet()}") },
                engine.authorizer,
                world.clock,
                // Four eyes on: the platform's own change is analysed, as anyone's but a lone trust root's is.
                ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
                revocations = RevocationReader.NONE,
                lockout = LockoutCheck.UNCHECKED,
                listeners = ChangeListeners(listOf(recorder)),
            )
            val a0 = SnapshotKey.App("a0")
            val head = runBlocking { world.revisions.head(a0)!!.revision }
            val operation = Operation.PutCatalog(
                Catalog(listOf(CatalogGroup("all", permissions = permissions(AppId("a0"), widened = true))))
            )
            val (proposed, proposeMs) = wall {
                runBlocking {
                    changes.propose(
                        plato,
                        a0,
                        head,
                        listOf(operation),
                        "widen p1"
                    )
                }
            }
            println(stepRow(n, "propose", proposed, proposeMs, world.counting))
            var change = proposed
            if (change.state == ChangeState.CHECKED) {
                world.counting.reset()
                val (skipped, ms) = wall { runBlocking { changes.skipShadow(plato, change.id, "measure") } }
                println(stepRow(n, "skipShadow", skipped, ms, world.counting))
                change = skipped
            }
            if (change.state == ChangeState.AWAITING_APPROVAL) {
                world.counting.reset()
                val (approved, ms) = wall { runBlocking { changes.approve(pia, change.id, change.candidate!!) } }
                println(stepRow(n, "approve (pia)", approved, ms, world.counting))
                change = approved
            }
            world.counting.reset()
            val (published, publishMs) = wall { runBlocking { changes.publish(plato, change.id) } }
            println(stepRow(n, "publish (with moved-head detection)", published, publishMs, world.counting))
            val audit = recorder.events.map { it.kind.name to ChangeEventJson.write(it).length }
            println(
                "|  | audit events | ${audit.joinToString { "${it.first} ${it.second / 1024} KB" }} | | " +
                    "moved: ${recorder.events.flatMap { it.moved }.size}; detection escalations: " +
                    "${recorder.events.flatMap { it.escalations }.filter { !it.informational }.map { it.reason }.distinct()} | | | |"
            )
            System.out.flush()
        }
    }

    // ---- R2C-25: one policy of S statements ----

    private val acme = TenantId("acme")
    private val analyzer = GrantAnalyzer()

    private fun statement(i: Int, shape: String, widened: Boolean = false): StatementSource = when (shape) {
        // Each statement its own action: narrows() fails at the first action.
        "distinct actions" -> StatementSource(
            "s$i",
            Effect.ALLOW,
            listOf("svc$i:read"),
            listOf(ResourceSource(if (widened) "order/**" else "order/$i/**"))
        )
        // One action, a resource each: narrows() reaches resource containment for every pair.
        else -> StatementSource(
            "s$i",
            Effect.ALLOW,
            listOf("order:read"),
            listOf(ResourceSource(if (widened) "order/**" else "order/$i/**"))
        )
    }

    private fun bound(id: String, role: String, policy: String) =
        Binding(
            BindingId(id),
            SubjectRef.Role(acme, RoleId(role)),
            BindingScope(Match.Any, Match.Exactly(SpaceId.DEFAULT)),
            PolicyId(policy)
        )

    private fun tenantDocument(policies: List<PolicyDefinition>, bindings: List<Binding>) =
        PolicyDocument(DocumentKind.TENANT, acme.value, 1, NOW, policies, bindings)

    private fun bigPolicy(s: Int, shape: String, widenLast: Boolean = false) =
        PolicyDefinition(
            PolicyId("big"),
            false,
            null,
            (0 until s).map { statement(it, shape, widenLast && it == s - 1) }
        )

    private val admin = Grantor(
        acme,
        PrincipalId("alice"),
        Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId.DEFAULT to setOf(RoleId("admin"))), emptyList()),
        emptyMap(),
        true,
    )

    private fun snapshot(base: PolicyDocument) =
        GrantDocuments.snapshot(null, emptyMap(), mapOf(acme to base), NOW.toEpochMilli(), Dimension.Only(emptySet()))

    // stays: a measurement prints one table from start to end, which reads best as one function.
    @Suppress("LongMethod")
    @Test
    fun bigPolicy() {
        println(
            "### R2C-25: a tenant document with one policy of S ALLOW statements, the grantor holding `order:*` on `order/**`\n"
        )
        println(
            "| shape | S | change | widened() (granted() over every policy) | analyze() | fragments | verdicts | samePolicy(previous, policy) |"
        )
        println("|---|---|---|---|---|---|---|---|")
        val equality = GrantEquality(ConditionChecker())
        for (shape in listOf("distinct actions", "one action")) {
            for (s in listOf(2_000, 10_000, 20_000)) {
                val held = PolicyDefinition(
                    PolicyId("held"),
                    false,
                    null,
                    listOf(StatementSource("all", Effect.ALLOW, listOf("*"), listOf(ResourceSource("order/**"))))
                )
                val base = tenantDocument(
                    listOf(held, bigPolicy(s, shape)),
                    listOf(bound("admin", "admin", "held"), bound("big", "staff", "big"))
                )
                val other = PolicyDefinition(
                    PolicyId("other"),
                    false,
                    null,
                    listOf(
                        StatementSource("o", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource("order/1/**")))
                    )
                )
                val none = tenantDocument(base.policies + other, base.bindings + bound("other", "staff", "other"))
                val one = tenantDocument(listOf(held, bigPolicy(s, shape, widenLast = true)), base.bindings)
                val snapshot = snapshot(base)
                val before = GrantDocuments.of(base)
                for ((name, candidate) in listOf(
                    "none of its statements" to none,
                    "its last statement widened" to one
                )) {
                    val after = GrantDocuments.of(candidate)
                    val change = GrantChange(acme, after)
                    val widenedMs = cpuMs { analyzer.widened(before, after) }
                    // The dearest row is computed from widened(): analyze() pays granted() once more, then the verdicts.
                    var fragments = -1
                    var verdicts = "(not run)"
                    val analyzeMs = if (shape == "one action" && s > 10_000) {
                        -1
                    } else {
                        cpuMs {
                            val analysis = analyzer.analyze(admin, snapshot, change)
                            fragments = analysis.verdicts.size
                            verdicts = analysis.verdicts.groupingBy { it.second::class.simpleName }.eachCount().toString()
                        }
                    }
                    val sameMs = cpuMs {
                        equality.samePolicy(
                            before.policy(PolicyId("big"))!!,
                            after.policy(PolicyId("big"))!!
                        )
                    }
                    println(
                        "| $shape | $s | $name | $widenedMs ms | $analyzeMs ms | $fragments | $verdicts | $sameMs ms |"
                    )
                    System.out.flush()
                }
            }
        }
    }

    // ---- R2C-26: a grantor holding A statements ----

    @Test
    fun grantorRights() {
        println("### R2C-26: the verdicts of 100 fragments against a grantor holding A ALLOWs (and A/10 DENYs)\n")
        println(
            "| held shape | A (held ALLOWs) | fragments covered by | rights built | verdicts, all | per fragment | outcome |"
        )
        println("|---|---|---|---|---|---|---|")
        for (round in 0..1) {
            for (shape in listOf("an action each", "one action, a resource each")) {
                for (a in listOf(2, 200, 2_000, 20_000)) {
                    val row = grantorRow(shape, a)
                    if (round == 1) row.forEach(::println)
                    System.out.flush()
                }
            }
        }
    }

    // stays: a measurement prints one table from start to end, which reads best as one function.
    @Suppress("LongMethod")
    private fun grantorRow(shape: String, a: Int): List<String> {
        val service = { i: Int -> if (shape == "an action each") "svc$i" else "svc" }
        val heldAllow = PolicyDefinition(
            PolicyId("held"),
            false,
            null,
            (0 until a).map {
                StatementSource("h$it", Effect.ALLOW, listOf("${service(it)}:*"), listOf(ResourceSource("res/$it/**")))
            }
        )
        val heldDeny = PolicyDefinition(
            PolicyId("held-deny"),
            false,
            null,
            (0 until maxOf(1, a / 10)).map {
                StatementSource(
                    "d$it",
                    Effect.DENY,
                    listOf("${service(it)}:delete"),
                    listOf(ResourceSource("res/$it/**"))
                )
            }
        )
        val base = tenantDocument(
            listOf(heldAllow, heldDeny),
            listOf(bound("admin", "admin", "held"), bound("admin-deny", "admin", "held-deny"))
        )
        val snapshot = snapshot(base)
        val rows = ArrayList<String>()
        for ((where, pick) in listOf<Pair<String, (Int) -> Int>>(
            "the last held ALLOWs" to { j -> a - 1 - (j % a) },
            "nothing held (not covered)" to { j -> a + j },
        )) {
            val granted = PolicyDefinition(
                PolicyId("granted"),
                false,
                null,
                (0 until 100).map { j ->
                    StatementSource(
                        "g$j",
                        Effect.ALLOW,
                        listOf("${service(pick(j))}:read"),
                        listOf(ResourceSource("res/${pick(j)}/x"))
                    )
                }
            )
            val candidate = tenantDocument(
                base.policies + granted,
                base.bindings + bound("granted", "member", "granted")
            )
            val change = GrantChange(acme, GrantDocuments.of(candidate))
            val fragments = analyzer.fragments(snapshot, change)
            lateinit var rights: GrantorRights
            val rightsMs = cpuMs { rights = GrantorRights.of(admin, snapshot) }
            var outcome: Map<String?, Int> = emptyMap()
            val start = threads.currentThreadCpuTime
            // Repeated so the small rows are above the clock's resolution.
            val rounds = if (a < 1_000) 20 else 1
            repeat(rounds) {
                outcome = fragments.map { analyzer.verdict(admin, rights, it) }
                    .groupingBy { v: GrantVerdict -> v::class.simpleName }.eachCount()
            }
            val totalUs = (threads.currentThreadCpuTime - start) / 1_000 / rounds
            rows += "| $shape | $a | $where | $rightsMs ms | ${totalUs / 1000.0} ms | ${totalUs / fragments.size} µs | $outcome |"
        }
        return rows
    }

    // ---- CTL-49: the budget, sized from the largest legitimate changes after the indexes ----

    private fun everyPermission(prefix: String) = APPS.map { app ->
        RoleDefinition(RoleId("$prefix-${app.value}"), RolePermissions.Listed((0 until 100).map { "p$it" }), app = app)
    }

    // stays: a measurement prints one table from start to end, which reads best as one function.
    @Suppress("LongMethod")
    @Test
    fun largestLegitimate() {
        println(
            "### CTL-49: comparisons of the largest legitimate changes, and the CPU of an analysis that spends the budget\n"
        )
        println("| change | fragments | comparisons | CPU | verdicts |")
        println("|---|---|---|---|---|")
        val apps = APPS.associateWith { app(it) as PolicyDocument? }
        // A tenant administrator holding a role of every permission of every app grants a role of each to staff.
        val holder = Grantor(
            acme,
            PrincipalId("alice"),
            Memberships(
                Memberships.Source.CLAIMS,
                mapOf(
                    SpaceId.DEFAULT to APPS.map {
                        RoleId("admin-${it.value}")
                    }.toSet()
                ),
                emptyList()
            ),
            emptyMap(),
            true,
        )
        val base = PolicyDocument(
            DocumentKind.TENANT,
            acme.value,
            1,
            NOW,
            emptyList(),
            emptyList(),
            roles = everyPermission("admin")
        )
        val candidate = PolicyDocument(
            DocumentKind.TENANT,
            acme.value,
            1,
            NOW,
            emptyList(),
            emptyList(),
            roles = everyPermission("admin") + everyPermission("staff")
        )
        val snapshot = GrantDocuments.snapshot(
            null,
            apps,
            mapOf(acme to base),
            NOW.toEpochMilli(),
            Dimension.Only(emptySet())
        )
        repeat(2) { round ->
            var analysis: me.ahoo.cosec.control.grant.GrantAnalysis? = null
            val ms =
                cpuMs { analysis = analyzer.analyze(holder, snapshot, GrantChange(acme, GrantDocuments.of(candidate))) }
            if (round == 1) {
                println(
                    "| tenant: 12 roles × 100 permissions granted by a holder of the same | ${analysis!!.verdicts.size} | ${analysis!!.comparisons} | $ms ms | " +
                        "${analysis!!.verdicts.groupingBy { it.second::class.simpleName }.eachCount()} |"
                )
            }
        }
        // An app change of 100 permissions: each widened, as the referring-bindings analysis reads it.
        val before = GrantDocuments.of(app(AppId("a0")))
        val widenedApp = PolicyDocument(
            DocumentKind.APP,
            "a0",
            1,
            NOW,
            emptyList(),
            emptyList(),
            Catalog(
                listOf(
                    CatalogGroup(
                        "all",
                        permissions = (0 until 100).map {
                            CatalogPermission("p$it", actions = listOf("a0:op$it", "a0:op$it-export"))
                        }
                    )
                )
            ),
            roles = app(AppId("a0")).roles,
        )
        val budget = me.ahoo.cosec.control.grant.ComparisonBudget()
        val equality = GrantEquality(ConditionChecker())
        val fragmenter = me.ahoo.cosec.control.grant.GrantFragmenter(
            me.ahoo.cosec.control.grant.GrantNarrowing(ConditionChecker(), equality),
            equality
        )
        val widened = fragmenter.widened(before, GrantDocuments.of(widenedApp), budget)
        println("| app: 100 permissions widened | ${widened.size} policies | ${budget.spent} | | |")
        // The dearest comparisons: a holder of 20,000 ALLOWs of one action, 2,000 fragments it does not cover.
        val held = PolicyDefinition(
            PolicyId("held"),
            false,
            null,
            (0 until 20_000).map {
                StatementSource("h$it", Effect.ALLOW, listOf("svc:*"), listOf(ResourceSource("res/$it/**")))
            }
        )
        val granted = PolicyDefinition(
            PolicyId("granted"),
            false,
            null,
            (0 until 2_000).map {
                StatementSource("g$it", Effect.ALLOW, listOf("svc:read"), listOf(ResourceSource("other/$it")))
            }
        )
        val worstBase = tenantDocument(listOf(held), listOf(bound("admin", "admin", "held")))
        val worst = tenantDocument(listOf(held, granted), worstBase.bindings + bound("granted", "member", "granted"))
        repeat(2) { round ->
            var analysis: me.ahoo.cosec.control.grant.GrantAnalysis? = null
            val ms =
                cpuMs { analysis = analyzer.analyze(admin, snapshot(worstBase), GrantChange(acme, GrantDocuments.of(worst))) }
            if (round == 1) {
                println(
                    "| worst: 2,000 fragments against 20,000 held ALLOWs of one action | ${analysis!!.verdicts.size} | ${analysis!!.comparisons} | $ms ms | " +
                        "${analysis!!.verdicts.groupingBy { (it.second as? me.ahoo.cosec.control.grant.GrantVerdict.Unknown)?.reason?.name ?: it.second::class.simpleName }.eachCount()} |"
                )
            }
        }
    }
}
