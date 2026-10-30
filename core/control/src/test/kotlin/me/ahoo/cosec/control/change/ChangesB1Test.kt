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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.engine.flat.StatementSource
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
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The change flow of RFC 0007 §6.1, §6.2 and §6.7 case by case. */
/**
 * RC review batch B1 through the change flow: the app list and every app document the analysis reads (RC1-4), tenant
 * and platform policy ids (RC1-9), what a publish finds moved after its write (RC1-33), and the tenant bindings an app
 * change widens (RC1-34).
 */
class ChangesB1Test {
    private val harness = ChangeHarness(options = ChangeOptions(maxDocumentBytes = 2_000, shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    @Test
    fun `widening an app permission policy lists the tenant bindings it widens - RC1-34`() = runBlocking<Unit> {
        val order = SnapshotKey.App("order")
        publish(
            order,
            Operation.PutPolicy(allow("perm:order/read", action = "order:read")),
            Operation.PutPolicy(allow("perm:order/write", action = "order:write")),
        )
        publish(
            ACME,
            Operation.PutBinding(bind("readers", "perm:order/read", app = "order")),
            Operation.PutBinding(bind("writers", "perm:order/write", app = "order")),
            Operation.PutBinding(
                binding(
                    "bob",
                    SubjectRef.Principal(TenantId("acme"), PrincipalId("bob")),
                    "perm:order/read",
                    "order",
                    SpaceId("team-a")
                )
            ),
        )
        // Four-eyes, so that the platform's own change is analyzed as far as an app document is.
        val ids = java.util.concurrent.atomic.AtomicInteger()
        val fourEyes = Changes(
            harness.memory,
            harness.revisions,
            { ChangeId("four-eyes-${ids.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
        )
        val widened = fourEyes.propose(
            PLATO,
            order,
            1,
            listOf(Operation.PutPolicy(allow("perm:order/read", action = "order:*"))),
            "widen",
        )
        widened.escalations.assert().containsExactly(
            Escalation("document app:order", "NOT_ANALYZED", "only the trust root publishes it alone"),
            Escalation(
                "binding readers of [tenant:acme]",
                "TENANT_BINDING",
                "[perm:order/read] statements [0] widen what this binding grants",
            ),
            Escalation(
                "binding bob of [tenant:acme]",
                "TENANT_BINDING",
                "[perm:order/read] statements [0] widen what this binding grants",
            ),
        )
        val narrowed = fourEyes.propose(
            PLATO,
            order,
            1,
            listOf(Operation.PutPolicy(allow("perm:order/read", action = "order:read", resource = "order/1"))),
            "narrow",
        )
        narrowed.escalations.map { it.reason }.assert().containsExactly("NOT_ANALYZED")
    }

    /** Changes over [store] with four-eyes on, so that every operator's change is analyzed as far as it goes. */
    private fun fourEyes(
        store: ControlStore = harness.memory,
        listeners: ChangeListeners = ChangeListeners.NONE
    ): Changes {
        val ids = java.util.concurrent.atomic.AtomicInteger()
        return Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("four-eyes-${ids.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
            listeners = listeners,
        )
    }

    private fun deny(id: String, action: String) = PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = listOf(StatementSource("", Effect.DENY, listOf(action))),
    )

    private fun binding(id: String, subject: SubjectRef, policy: String, app: String? = null, space: SpaceId? = null) =
        Binding(
            BindingId(id),
            subject,
            BindingScope(
                app?.let { Match.Exactly(AppId(it)) } ?: Match.Any,
                space?.let { Match.Exactly(it) } ?: Match.Any
            ),
            PolicyId(policy),
        )

    /** RC1-4: billing denies order:delete to the role ops of every tenant; the grants below never name billing. */
    private suspend fun billing() {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("platform-read")))
        publish(
            SnapshotKey.App("billing"),
            Operation.PutPolicy(deny("perm:billing/no-delete", "order:delete")),
            Operation.PutBinding(
                binding("no-delete", SubjectRef.Role(null, RoleId("ops")), "perm:billing/no-delete", "billing")
            ),
        )
        publish(
            ACME,
            Operation.PutPolicy(allow("orders", action = "order:*", resource = "order/**")),
            Operation.PutBinding(
                binding("ops", SubjectRef.Role(null, RoleId("ops")), "orders", space = SpaceId.DEFAULT)
            ),
        )
    }

    private val ops = Operator(
        ALICE,
        Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId.DEFAULT to setOf(RoleId("ops"))), emptyList())
    )

    /** What [ops] granting bob `orders` in every app needs, with carol granted it in [alsoIn] by the same change. */
    private suspend fun grant(changes: Changes, alsoIn: String? = null): List<String> {
        fun to(id: String, app: String?) =
            Operation.PutBinding(
                binding(id, SubjectRef.Principal(TenantId("acme"), PrincipalId(id)), "orders", app, SpaceId.DEFAULT)
            )
        val operations = listOfNotNull(to("bob", null), alsoIn?.let { to("carol", it) })
        return changes.propose(ops, ACME, 1, operations, "grant").escalations.map { it.reason }.distinct()
    }

    @Test
    fun `the analysis reads every app document the app list names - RC1-4`() = runBlocking<Unit> {
        billing()
        harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = true))
        val unreadable = HashSet<SnapshotKey>()
        val store = object : ControlStore by harness.memory {
            override suspend fun head(key: SnapshotKey): StoredDocument? {
                check(key !in unreadable) { "[$key] cannot be read" }
                return harness.memory.head(key)
            }
        }
        val changes = fourEyes(store)
        grant(changes).assert().containsExactly("DENY_OVERLAP")
        unreadable += SnapshotKey.App("billing")
        grant(changes).assert().containsExactly("UNREADABLE")
    }

    @Test
    fun `an app list that is not to be trusted leaves every app unknown - RC1-4`() = runBlocking<Unit> {
        billing()
        var failing = false
        val read = HashSet<SnapshotKey>()
        val store = object : ControlStore by harness.memory {
            override suspend fun apps(): Versioned<AppList>? {
                check(!failing) { "the app list cannot be read" }
                return harness.memory.apps()
            }

            override suspend fun head(key: SnapshotKey): StoredDocument? = harness.memory.head(key).also { read += key }
        }
        val changes = fourEyes(store)
        suspend fun list(apps: AppList?) {
            harness.memory.dropApps()
            apps?.let { harness.memory.putApps(null, it).assert().isTrue() }
        }
        // Missing, as on a deployment upgraded from alpha.2 before repair-references.
        list(null)
        grant(changes).assert().containsExactly("UNREADABLE")
        // Incomplete, as when it was lost and an app document's publish started it again.
        list(AppList(setOf("billing"), complete = false))
        grant(changes).assert().containsExactly("UNREADABLE")
        // An entry that names no app document: it may be one the analysis would miss, so no entry is trusted, and the
        // apps it does name are not even read.
        for (odd in listOf("not:an app", "", "*")) {
            list(AppList(setOf("billing", odd), complete = true))
            read.clear()
            grant(changes).assert().describedAs(odd).containsExactly("UNREADABLE")
            read.assert().describedAs(odd).doesNotContain(SnapshotKey.App("billing"))
        }
        // It cannot be read.
        list(AppList(setOf("billing"), complete = true))
        failing = true
        grant(changes).assert().containsExactly("UNREADABLE")
        failing = false
        // Complete, but without an app whose head exists and the change names: it misses apps, so bob's grant in every
        // app is unknown, though billing itself is read for carol's.
        list(AppList(emptySet(), complete = true))
        grant(changes, alsoIn = "billing").assert().contains("UNREADABLE")
        // Complete and right: billing's DENY is read again.
        list(AppList(setOf("billing"), complete = true))
        read.clear()
        grant(changes).assert().containsExactly("DENY_OVERLAP")
        read.assert().contains(SnapshotKey.App("billing"))
    }

    /** Review of #1181 (N3): the platform's first revision vouches for the list only when nothing says it misses an app. */
    @Test
    fun `the platform's first revision completes the app list only when it names every app document - RC1-4`() =
        runBlocking<Unit> {
            val revisions = harness.revisions
            val billing = SnapshotKey.App("billing")
            val order = SnapshotKey.App("order")
            // The platform's head was lost; billing and order are still there, and order alone rebuilt a lost list.
            revisions.publish(billing, null, StoreFixtures.candidate(billing), StoreFixtures.publication())
            revisions.publish(order, null, StoreFixtures.candidate(order), StoreFixtures.publication())
            harness.memory.dropApps()
            revisions.publish(order, revisions.head(order), StoreFixtures.candidate(order), StoreFixtures.publication())
            harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("order"), complete = false))
            revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(SnapshotKey.Platform),
                StoreFixtures.publication()
            ).assert().isInstanceOf(PublishOutcome.Published::class.java)
            harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("order"), complete = false))
        }

    @Test
    fun `a platform's first revision that does not land leaves the app list as it was - RC1-4`() = runBlocking<Unit> {
        val conflicting = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite = HeadWrite.Conflict(null)
        }
        Revisions(conflicting, harness.clock).publish(
            SnapshotKey.Platform,
            null,
            StoreFixtures.candidate(SnapshotKey.Platform),
            StoreFixtures.publication()
        ).assert().isInstanceOf(PublishOutcome.Conflicted::class.java)
        harness.memory.apps().assert().isNull()
    }

    /**
     * Review of #1181: the platform's first revision completes a list that names every app document with a head; an
     * app listed without a head (a first publish that stopped before it) does not count, one whose head cannot be read
     * does.
     */
    @Test
    fun `the platform's first revision completes a list that names every app with a head - RC1-4`() =
        runBlocking<Unit> {
            val billing = SnapshotKey.App("billing")
            val ghost = SnapshotKey.App("ghost")
            harness.revisions.publish(billing, null, StoreFixtures.candidate(billing), StoreFixtures.publication())
            harness.memory.putIndex(ghost, null, RevisionIndex()).assert().isTrue()
            var failing = true
            val store = object : ControlStore by harness.memory {
                override suspend fun head(key: SnapshotKey): StoredDocument? {
                    check(!(failing && key == ghost)) { "Redis is down" }
                    return harness.memory.head(key)
                }

                override suspend fun putHead(
                    key: SnapshotKey,
                    expected: SnapshotDigest?,
                    document: SnapshotDocument
                ): HeadWrite {
                    if (failing) {
                        return HeadWrite.Conflict(null)
                    }
                    return harness.memory.putHead(key, expected, document)
                }
            }
            val revisions = Revisions(store, harness.clock)
            val platform = StoreFixtures.candidate(SnapshotKey.Platform)
            // The ghost's head cannot be read: it may have one, so a first revision would not complete the list (this
            // one conflicts, which marks nothing anyway).
            revisions.publish(SnapshotKey.Platform, null, platform, StoreFixtures.publication())
                .assert().isInstanceOf(PublishOutcome.Conflicted::class.java)
            harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = false))
            failing = false
            revisions.publish(SnapshotKey.Platform, null, platform, StoreFixtures.publication())
                .assert().isInstanceOf(PublishOutcome.Published::class.java)
            harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = true))
        }

    /** The same, when the ghost's head cannot be read at the platform's first revision. */
    @Test
    fun `an app whose head cannot be read keeps the platform's first revision from completing the list - RC1-4`() =
        runBlocking<Unit> {
            val ghost = SnapshotKey.App("ghost")
            harness.memory.putIndex(ghost, null, RevisionIndex()).assert().isTrue()
            val store = object : ControlStore by harness.memory {
                override suspend fun head(key: SnapshotKey): StoredDocument? {
                    check(key != ghost) { "Redis is down" }
                    return harness.memory.head(key)
                }
            }
            Revisions(store, harness.clock).publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(SnapshotKey.Platform),
                StoreFixtures.publication()
            ).assert().isInstanceOf(PublishOutcome.Published::class.java)
            harness.memory.apps()?.value?.complete.assert().isNotEqualTo(true)
        }

    /** Review of #1181: Revisions' own steps after the head write, apart from what Changes does around them. */
    @Test
    fun `Revisions finishes what follows its head write when the caller is cancelled - RC1-33`() = runBlocking<Unit> {
        val written = CompletableDeferred<Unit>()
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite = harness.memory.putHead(key, expected, document).also {
                written.complete(Unit)
                delay(100)
            }

            override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
                yield()
                return harness.memory.putIndex(key, expected, index)
            }

            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean {
                yield()
                return harness.memory.putApps(expected, apps)
            }
        }
        val after = CompletableDeferred<PublishOutcome.Published>()
        val publishing = launch {
            Revisions(store, harness.clock).publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(SnapshotKey.Platform),
                StoreFixtures.publication()
            ) {
                yield()
                after.complete(it)
            }
        }
        written.await()
        publishing.cancelAndJoin()
        harness.memory.index(SnapshotKey.Platform)!!.value.entries.map { it.revision }.assert().containsExactly(1L)
        harness.memory.apps()!!.value.complete.assert().isTrue()
        after.isCompleted.assert().isTrue()
    }

    /** Review of #1181 (F2): a revision in effect is published, whatever its index does afterwards. */
    @Test
    fun `a failure after the head write is logged and the revision is published - RC1-33`() = runBlocking<Unit> {
        var written = false
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite = harness.memory.putHead(key, expected, document).also { written = true }

            override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
                check(!written) { "Redis is down" }
                return harness.memory.putIndex(key, expected, index)
            }

            override suspend fun putReferrers(
                target: SnapshotKey,
                policy: PolicyId,
                expected: SnapshotDigest?,
                referrers: Set<SnapshotKey>
            ): Boolean {
                check(!written) { "Redis is down" }
                return harness.memory.putReferrers(target, policy, expected, referrers)
            }
        }
        val revisions = Revisions(store, harness.clock)
        revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        // The index missed the revision; history() finds it along the chain.
        harness.memory.index(ACME)!!.value.entries.assert().isEmpty()
        harness.revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
    }

    /** Review of #1181 (F2): once the head is written, what moved is recorded even when the change cannot be settled. */
    @Test
    fun `what moved is recorded even when a written change cannot be settled - RC1-33`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("platform-read")))
        var written = false
        val readAfter = java.util.Collections.synchronizedList(ArrayList<SnapshotKey>())
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite = harness.memory.putHead(key, expected, document).also { written = key == ACME }

            override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean {
                check(!written) { "Redis is down" }
                return harness.memory.putChange(expected, change)
            }

            override suspend fun head(key: SnapshotKey): StoredDocument? =
                harness.memory.head(key).also { if (written) readAfter += key }
        }
        val changes = Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("unsettled") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
        )
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("read"))), "c")
        changes.skipShadow(ALICE_OP, change.id, "test")
        val failure = try {
            changes.publish(ALICE_OP, change.id)
            null
        } catch (e: IllegalStateException) {
            e
        }
        failure?.message.assert().isEqualTo("Redis is down")
        harness.revisions.head(ACME)!!.revision.assert().isEqualTo(1L)
        // The detection compared the platform's head, which the publish was checked with.
        readAfter.assert().contains(SnapshotKey.Platform)
    }

    /** Review of #1181 (N1): a client that goes away after the head write leaves the change settled and audited. */
    @Test
    fun `a publish cancelled after its head write still settles as published - RC1-33`() = runBlocking<Unit> {
        val written = CompletableDeferred<Unit>()
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite = harness.memory.putHead(key, expected, document).also {
                written.complete(Unit)
                // Where the caller is cancelled: what follows must still run.
                delay(100)
            }

            // The writes after the head suspend, as a real store's do, so that a cancellation would be seen there.
            override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
                yield()
                return harness.memory.putIndex(key, expected, index)
            }

            override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean {
                yield()
                return harness.memory.putChange(expected, change)
            }
        }
        val events = java.util.Collections.synchronizedList(ArrayList<ChangeEvent.Kind>())
        val listener = object : ChangeListener {
            override val name = "events"

            override suspend fun onChange(event: ChangeEvent) {
                events += event.kind
            }
        }
        val changes = Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("cancelled") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
            listeners = ChangeListeners(listOf(listener)),
        )
        val change = changes.propose(PLATO, SnapshotKey.Platform, 0, listOf(Operation.PutPolicy(allow("read"))), "c")
        changes.skipShadow(PLATO, change.id, "test")
        val publishing = launch { changes.publish(PLATO, change.id) }
        written.await()
        publishing.cancelAndJoin()
        harness.memory.change(change.id)!!.value.state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.memory.index(SnapshotKey.Platform)!!.value.entries.map { it.revision }.assert().containsExactly(1L)
        harness.memory.apps()!!.value.complete.assert().isTrue()
        withTimeout(5_000) {
            while (ChangeEvent.Kind.PUBLISHED !in events) delay(10)
        }
    }

    @Test
    fun `an app document lists itself, and a lost list starts again incomplete - RC1-4`() = runBlocking<Unit> {
        // A new store: the platform's first revision makes the list, complete.
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("platform-read")))
        harness.memory.apps()!!.value.assert().isEqualTo(AppList(emptySet(), complete = true))
        publish(SnapshotKey.App("billing"), Operation.PutPolicy(allow("perm:billing/read")))
        harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = true))
        // Lost: the next app publish lists itself again, in a list that says it may miss others.
        harness.memory.dropApps()
        publish(SnapshotKey.App("order"), Operation.PutPolicy(allow("perm:order/read")))
        harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("order"), complete = false))
        // A later platform revision does not vouch for it.
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("platform-write")))
        harness.memory.apps()!!.value.complete.assert().isFalse()
        // repair-references completes it from every document the store lists.
        harness.revisions.completeApps(setOf("billing", "order")).assert().isTrue()
        harness.memory.apps()!!.value.assert().isEqualTo(AppList(setOf("billing", "order"), complete = true))
    }

    @Test
    fun `the platform cannot add an id a tenant uses, and one that only defines it never blocks a removal - RC1-9`() =
        runBlocking<Unit> {
            publish(ACME, Operation.PutPolicy(allow("editor")), Operation.PutBinding(bind("staff", "editor")))
            val platform = SnapshotKey.Platform
            val added = changes.propose(
                PLATO,
                platform,
                0,
                listOf(Operation.PutPolicy(allow("editor", action = "order:*"))),
                "add"
            )
            added.errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
            added.errors.single().message.assert().contains("tenant:acme")
            changes.propose(
                PLATO,
                platform,
                0,
                listOf(Operation.PutPolicy(allow("other"))),
                "other"
            ).errors.assert().isEmpty()
            // A collision from before the upgrade, written as cosec-migrate writes: acme defines editor itself, so it
            // does not keep the platform from removing its own, which ends the ambiguity.
            harness.revisions.publish(
                platform,
                null,
                StoreFixtures.candidate(platform, listOf(allow("editor"))),
                StoreFixtures.publication()
            )
            changes.propose(PLATO, platform, 1, listOf(Operation.RemovePolicy(PolicyId("editor"))), "remove")
                .errors.assert().isEmpty()
            // A tenant that binds the platform's editor does.
            val beta = SnapshotKey.Tenant("beta")
            harness.revisions.publish(
                beta,
                null,
                StoreFixtures.candidate(beta, bindings = listOf(bind("staff", "editor"))),
                StoreFixtures.publication()
            )
            changes.propose(PLATO, platform, 1, listOf(Operation.RemovePolicy(PolicyId("editor"))), "remove")
                .errors.map {
                    it.check to it.message
                }.assert().containsExactly("REFERENCES" to "[editor] is referred to by [tenant:beta]")
        }

    /** RC1-33: what the publish of [grant] by alice records when [meanwhile] lands on the platform just before its write. */
    private suspend fun publishInterleaved(meanwhile: List<PolicyDefinition>?): List<ChangeEvent> {
        val alice = SubjectRef.Principal(TenantId("acme"), PrincipalId("alice"))
        publish(
            SnapshotKey.Platform,
            Operation.PutPolicy(allow("orders", action = "order:*", resource = "order/**")),
            Operation.PutBinding(binding("alice", alice, "orders")),
        )
        val inner = Revisions(harness.memory, harness.clock)
        var pending = meanwhile
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite {
                val platform = pending
                if (key == ACME && platform != null) {
                    pending = null
                    val head = inner.head(SnapshotKey.Platform)!!
                    val candidate = StoreFixtures.candidate(
                        SnapshotKey.Platform,
                        head.document.policies + platform,
                        head.document.bindings + platform.filter { it.statements.single().effect == Effect.DENY }
                            .map { binding(it.id.value, alice, it.id.value) },
                    )
                    inner.publish(SnapshotKey.Platform, head, candidate, StoreFixtures.publication("meanwhile"))
                        .assert().isInstanceOf(PublishOutcome.Published::class.java)
                }
                return harness.memory.putHead(key, expected, document)
            }
        }
        val events = java.util.Collections.synchronizedList(ArrayList<ChangeEvent>())
        val listener = object : ChangeListener {
            override val name = "events"

            override suspend fun onChange(event: ChangeEvent) {
                events += event
            }
        }
        val changes = fourEyes(store, ChangeListeners(listOf(listener)))
        val grant = Operation.PutBinding(
            binding("bob", SubjectRef.Principal(TenantId("acme"), PrincipalId("bob")), "orders")
        )
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(grant), "grant")
        change.escalations.assert().isEmpty()
        changes.skipShadow(ALICE_OP, change.id, "test")
        changes.publish(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        withTimeout(5_000) {
            while (events.none { it.kind == ChangeEvent.Kind.PUBLISHED }) delay(10)
        }
        delay(200)
        return events.filter { it.kind == ChangeEvent.Kind.HEADS_MOVED || it.kind == ChangeEvent.Kind.REVIEW_NEEDED }
    }

    @Test
    fun `a restriction published during a grant's publish is detected, not rolled back - RC1-33`() = runBlocking<Unit> {
        val detected = publishInterleaved(listOf(deny("no-delete", "order:delete"))).single()
        detected.kind.assert().isEqualTo(ChangeEvent.Kind.REVIEW_NEEDED)
        detected.moved.map { it.document }.assert().containsExactly("platform")
        detected.escalations.map { it.reason }.distinct().assert().containsExactly("DENY_OVERLAP")
        harness.revisions.head(ACME)!!.document.bindings.map { it.id.value }.assert().containsExactly("bob")
    }

    @Test
    fun `an unrelated platform publish meanwhile is recorded as nothing new - RC1-33`() = runBlocking<Unit> {
        val detected = publishInterleaved(listOf(allow("unrelated", action = "user:read"))).single()
        detected.kind.assert().isEqualTo(ChangeEvent.Kind.HEADS_MOVED)
        detected.escalations.assert().isEmpty()
    }

    @Test
    fun `heads that did not move are not reported - RC1-33`() = runBlocking<Unit> {
        publishInterleaved(null).assert().isEmpty()
    }

    @Test
    fun `tenants that cannot be read refuse a widening app change - RC1-34`() = runBlocking<Unit> {
        val order = SnapshotKey.App("order")
        publish(order, Operation.PutPolicy(allow("perm:order/read", action = "order:read")))
        publish(ACME, Operation.PutBinding(bind("readers", "perm:order/read", app = "order")))
        val store = object : ControlStore by harness.memory {
            override suspend fun referrers(target: SnapshotKey, policy: PolicyId) =
                if (target == order) error("the index cannot be read") else harness.memory.referrers(target, policy)
        }
        assertThrownBy<ChangeRejectedException> {
            runBlocking {
                fourEyes(store).propose(
                    PLATO,
                    order,
                    1,
                    listOf(Operation.PutPolicy(allow("perm:order/read", action = "order:*"))),
                    "widen"
                )
            }
        }.extracting { (it as ChangeRejectedException).status }.isEqualTo(503)
    }

    @Test
    fun `a tenant document that cannot be read refuses a platform id and keeps blocking a removal - RC1-9`() =
        runBlocking<Unit> {
            publish(ACME, Operation.PutPolicy(allow("editor")), Operation.PutBinding(bind("staff", "editor")))
            var unreadable = false
            val store = object : ControlStore by harness.memory {
                override suspend fun head(key: SnapshotKey): StoredDocument? {
                    check(!(unreadable && key == ACME)) { "[$key] cannot be read" }
                    return harness.memory.head(key)
                }
            }
            val changes = fourEyes(store)
            unreadable = true
            val added = changes.propose(
                PLATO,
                SnapshotKey.Platform,
                0,
                listOf(Operation.PutPolicy(allow("editor", action = "order:*"))),
                "add"
            )
            added.errors.map { it.check to it.status }.assert().containsExactly("V2" to 503)
            added.errors.single().message.assert().contains("could not be read")
            // Written as cosec-migrate would, from before the upgrade: acme may define editor, but cannot be read, so
            // it may refer to the platform's and still blocks its removal.
            unreadable = false
            harness.revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(SnapshotKey.Platform, listOf(allow("editor"))),
                StoreFixtures.publication()
            )
            unreadable = true
            changes.propose(
                PLATO,
                SnapshotKey.Platform,
                1,
                listOf(Operation.RemovePolicy(PolicyId("editor"))),
                "remove"
            )
                .errors.map { it.check }.assert().containsExactly("REFERENCES")
        }

    @Test
    fun `a head that cannot be read after the write is reported for review - RC1-33`() = runBlocking<Unit> {
        val alice = SubjectRef.Principal(TenantId("acme"), PrincipalId("alice"))
        publish(
            SnapshotKey.Platform,
            Operation.PutPolicy(allow("orders", action = "order:*", resource = "order/**")),
            Operation.PutBinding(binding("alice", alice, "orders")),
        )
        var written = false
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument) =
                harness.memory.putHead(key, expected, document).also { if (key == ACME) written = true }

            override suspend fun head(key: SnapshotKey): StoredDocument? {
                check(!(written && key == SnapshotKey.Platform)) { "the platform cannot be read" }
                return harness.memory.head(key)
            }
        }
        val events = java.util.Collections.synchronizedList(ArrayList<ChangeEvent>())
        val listener = object : ChangeListener {
            override val name = "events"

            override suspend fun onChange(event: ChangeEvent) {
                events += event
            }
        }
        val changes = fourEyes(store, ChangeListeners(listOf(listener)))
        val grant = Operation.PutBinding(
            binding("bob", SubjectRef.Principal(TenantId("acme"), PrincipalId("bob")), "orders")
        )
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(grant), "grant")
        changes.skipShadow(ALICE_OP, change.id, "test")
        changes.publish(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        val detected = withTimeout(5_000) {
            while (events.none { it.kind == ChangeEvent.Kind.REVIEW_NEEDED }) delay(10)
            events.single { it.kind == ChangeEvent.Kind.REVIEW_NEEDED }
        }
        detected.moved.single().let {
            it.document.assert().isEqualTo("platform")
            it.readable.assert().isFalse()
        }
        detected.note.assert().contains("could not run again")
    }

    @Test
    fun `a tenant whose boundary names the id refers to the platform's, though it defines one too - RC1-9`() =
        runBlocking<Unit> {
            val platform = SnapshotKey.Platform
            harness.revisions.publish(
                platform,
                null,
                StoreFixtures.candidate(platform, listOf(allow("edge"))),
                StoreFixtures.publication()
            )
            val (kind, owner) = Revisions.ownerOf(ACME)
            val tenant = me.ahoo.cosec.runtime.document.PolicyDocument(
                kind,
                owner,
                1,
                StoreFixtures.START,
                listOf(allow("edge")),
                emptyList(),
                boundary = PolicyId("edge"),
            )
            harness.revisions.publish(ACME, null, tenant, StoreFixtures.publication())
            changes.propose(PLATO, platform, 1, listOf(Operation.RemovePolicy(PolicyId("edge"))), "remove")
                .errors.map { it.check }.assert().containsExactly("REFERENCES")
            harness.revisions.publish(
                platform,
                harness.revisions.head(platform),
                StoreFixtures.candidate(platform),
                StoreFixtures.publication()
            ).assert().isInstanceOf(PublishOutcome.Referenced::class.java)
        }
}
