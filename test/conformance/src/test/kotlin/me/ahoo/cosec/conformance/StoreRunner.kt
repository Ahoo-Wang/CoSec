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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The `control` runner for publishing (RFC 0007 §5.4, §5.8): a sequence of publishes to an in-memory `ControlStore`,
 * each of one document (`"document"`: `platform` or `tenant:<id>`, by default `tenant:acme`) with its own policies and
 * bindings of role `editor` to the named policies, on the current head or on the head before the previous publish of
 * that document (`"base": "stale"`), optionally with its head write failing (`"failHead": true`), or with what it does
 * to the back-reference index after its head write held until the next publish has finished (`"holdRemovals": true`,
 * #1207): the next publish then runs on the head it wrote. The outcome is what each publish answered, in the order of
 * the publishes, the head of the last publish's document afterwards, the records it holds, and the order of the writes.
 *
 * ```jsonc
 * "publishes": [ { "policies": ["p"] }, { "policies": ["q"], "bindings": ["shared"], "base": "stale" } ],
 * "expect": { "outcomes": ["Published", "Conflicted"],
 *             "head": { "revision": 1, "parent": null, "parentRecord": null },
 *             "records": 2, "writes": ["record", "head", "record", "head"] }
 * ```
 *
 * `parentRecord` is whether the record the head's parent names exists; `writes` lists record and head writes only.
 *
 * A publish may also move the head back to a retained revision first (`"restoreHead": 1`, as a partly restored store
 * does, the index and the records as they were), and fail the index write that follows its head write (`"failIndex":
 * true`). A scenario with `"history": true` also reads the history of the last document, `<revision>:<policies>` of the
 * document each entry reads, and `expect` has it: `"history": ["1:p", "2:q"]`.
 *
 * A publish may name policies whose back-reference sets cannot be read or written (`"unreadable": ["shared"]`, as a
 * value of the format before 8.0.0-beta.5, `CTL-42`). A scenario with `"cost": true` also counts what the last publish
 * did to the back-reference sets (`CTL-44`): `"lastPublish": {"setReads": 0, "membersAdded": 1, "membersRemoved": 0}`.
 *
 * A scenario with `"reclaim": true` runs a reclamation of the last document after its publishes, all younger than
 * `min-age`, with `"retention"` naming what it keeps (`CTL-84`): `"retention": {"maxCount": 2, "documentCap": 320}`.
 */
object StoreRunner : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome = runBlocking {
        scenario.node.only(
            "\$comment",
            "rules",
            "polarity",
            "level",
            "description",
            "publishes",
            "history",
            "cost",
            "retention",
            "reclaim",
            "expect",
        )
        val memory = InMemoryControlStore()
        val store = RecordingStore(memory)
        val revisions = Revisions(store, retention = retentionOf(scenario.node.get("retention")))
        val outcomes = ArrayList<Deferred<String>>()
        val previous = HashMap<SnapshotKey, Head?>()
        var key: SnapshotKey = SnapshotKey.Tenant("acme")
        var held: Pair<Hold, Deferred<String>>? = null
        for (publish in scenario.node.required("publishes").values()) {
            key = prepare(publish, memory, store)
            store.cost.clear()
            val current = revisions.head(key)
            val base = baseOf(publish, current, previous[key])
            val candidate = candidate(key, publish)
            val publication = Publication("c${outcomes.size}", ProvenanceKind.PUBLISH, ACTOR)
            val hold = if (publish.get("holdRemovals")?.bool() == true) Hold() else null
            val outcome = async(hold ?: Hold.NONE) { outcomeOf(revisions, key, base, candidate, publication) }
            if (hold != null) {
                // Until it waits to change the back-reference index after its head write, or ends without doing so.
                select {
                    hold.paused.onAwait {}
                    outcome.onAwait {}
                }
            } else {
                outcome.await()
                // The held publish finishes now, after the one that followed it.
                held?.let { (it, finishing) -> it.release.complete(Unit).also { finishing.await() } }
                held = null
            }
            store.failHead = false
            store.failIndex = false
            previous[key] = current
            outcomes += outcome
            hold?.let { held = it to outcome }
        }
        held?.let { (it, finishing) -> it.release.complete(Unit).also { finishing.await() } }
        if (scenario.node.get("reclaim")?.bool() == true) revisions.reclaim(key)
        stored(scenario, outcomes.map { it.await() }, revisions, memory, store, key)
    }

    /** `"retention": {"maxCount": 2, "documentCap": 320}`: what a reclamation keeps (`CTL-84`), the defaults otherwise. */
    private fun retentionOf(node: JsonNode?): RetentionPolicy {
        node?.only("maxCount", "documentCap") ?: return RetentionPolicy()
        val defaults = RetentionPolicy()
        return RetentionPolicy(
            maxCount = node.get("maxCount")?.intValue() ?: defaults.maxCount,
            documentCap = node.get("documentCap")?.longValue() ?: defaults.documentCap,
        )
    }

    /** What the scenario expects of the store after its publishes: [answered], the head of [key] and what it holds. */
    private suspend fun stored(
        scenario: Scenario,
        answered: List<String>,
        revisions: Revisions,
        memory: InMemoryControlStore,
        store: RecordingStore,
        key: SnapshotKey,
    ): Outcome.Stored {
        val head = revisions.head(key)
        val parent = head?.document?.provenance?.parent
        return Outcome.Stored(
            outcomes = answered,
            revision = head?.revision,
            parent = parent?.revision,
            parentRecord = parent?.let { memory.record(key, SnapshotDigest.parse(it.digest)) != null },
            records = memory.recordDigests(key).size,
            writes = store.writes,
            history = if (scenario.node.get("history")?.bool() == true) historyOf(revisions, key) else null,
            cost = if (scenario.node.get("cost")?.bool() == true) {
                COST_KEYS.associateWith { store.cost[it] ?: 0 }
            } else {
                null
            },
        )
    }

    /** The document of [publish], with what the publish asks of the store set: its head moved back, writes to fail. */
    private suspend fun prepare(publish: JsonNode, memory: InMemoryControlStore, store: RecordingStore): SnapshotKey {
        publish.only(
            "document",
            "policies",
            "bindings",
            "base",
            "failHead",
            "failIndex",
            "restoreHead",
            "holdRemovals",
            "unreadable",
        )
        val key = publish.get("document")?.text()?.let(::documentKey) ?: SnapshotKey.Tenant("acme")
        publish.get("restoreHead")?.let { restoreHead(memory, key, it.longValue()) }
        store.failHead = publish.get("failHead")?.bool() ?: false
        store.failIndex = publish.get("failIndex")?.bool() ?: false
        store.unreadable = publish.get("unreadable")?.values()?.mapTo(HashSet()) { PolicyId(it.text()) }.orEmpty()
        return key
    }

    /** The head moved back to retained revision [revision], the index and the records as they are (a partial loss). */
    private suspend fun restoreHead(memory: InMemoryControlStore, key: SnapshotKey, revision: Long) {
        val entry = memory.index(key)!!.value.entries.first { it.revision == revision }
        val head = memory.head(key)!!
        memory.putHead(key, head.digest, memory.record(key, entry.digest)!!)
    }

    /** The history of [key] as `<revision>:<policies of the document it reads>`, oldest first. */
    private suspend fun historyOf(revisions: Revisions, key: SnapshotKey): List<String> =
        revisions.history(key).map { entry ->
            "${entry.revision}:" + revisions.revision(key, entry.revision)!!.policies.joinToString(",") { it.id.value }
        }

    private suspend fun outcomeOf(
        revisions: Revisions,
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument,
        publication: Publication,
    ): String = try {
        when (revisions.publish(key, base, candidate, publication)) {
            is PublishOutcome.Published -> "Published"
            is PublishOutcome.Conflicted -> "Conflicted"
            is PublishOutcome.Referenced -> "Referenced"
        }
    } catch (_: IllegalStateException) {
        "Failed"
    }

    /** The base a publish names: the current head, or the one before the previous publish of its document. */
    private fun baseOf(publish: JsonNode, current: Head?, previous: Head?): Head? =
        when (val text = publish.get("base")?.text() ?: "head") {
            "head" -> current
            "stale" -> previous
            else -> throw ScenarioFormatException("A base is head or stale, not $text")
        }

    /**
     * [memory], recording its record and head writes, failing the head write while [failHead], and holding the
     * back-reference writes of a `holdRemovals` publish after its head write.
     */
    private class RecordingStore(private val memory: InMemoryControlStore) : ControlStore by memory {
        val writes = ArrayList<String>()
        var failHead = false

        /** Fails the index write that follows a head write: the one that no longer holds a pending record. */
        var failIndex = false

        /** The policies whose back-reference sets cannot be read or written, as a value of the old format (`CTL-42`). */
        var unreadable: Set<PolicyId> = emptySet()

        /** What the last publish did to the back-reference sets: reads, members added, members removed (`CTL-44`). */
        val cost = HashMap<String, Int>()

        private fun touch(policy: PolicyId, what: String, count: Int) {
            check(policy !in unreadable) { "the back-reference set of [${policy.value}] cannot be read" }
            cost.merge(what, count, Int::plus)
        }

        override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> {
            touch(policy, "setReads", 1)
            return memory.referrers(target, policy)
        }

        override suspend fun addReferrer(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry) {
            touch(policy, "membersAdded", 1)
            memory.addReferrer(target, policy, entry)
        }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            check(!(failIndex && index.pending.isEmpty())) { "the index write failed" }
            return memory.putIndex(key, expected, index)
        }

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
            writes += "record"
            return memory.putRecord(key, document)
        }

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite {
            writes += "head"
            check(!failHead) { "the head write failed" }
            return memory.putHead(key, expected, document).also {
                if (it is HeadWrite.Written) currentCoroutineContext()[Hold]?.written = true
            }
        }

        override suspend fun removeReferrers(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) {
            currentCoroutineContext()[Hold]?.takeIf { it.written }?.let { hold ->
                hold.paused.complete(Unit)
                hold.release.await()
            }
            touch(policy, "membersRemoved", entries.size)
            memory.removeReferrers(target, policy, entries)
        }
    }

    /** A publish whose back-reference changes after its head write wait for [release] (`"holdRemovals"`). */
    private class Hold : AbstractCoroutineContextElement(Hold) {
        var written = false
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        companion object Key : CoroutineContext.Key<Hold> {
            /** No hold: the context of every other publish. */
            val NONE: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
        }
    }

    private val COST_KEYS = listOf("setReads", "membersAdded", "membersRemoved")

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw ScenarioFormatException("A document is platform or tenant:<id>, not $text")
    }

    private fun candidate(key: SnapshotKey, publish: JsonNode): PolicyDocument {
        val (kind, owner) = ownerOf(key)
        return PolicyDocument(
            kind,
            owner,
            1,
            Instant.EPOCH,
            publish.get("policies")?.strings().orEmpty().map {
                PolicyDefinition(PolicyId(it), false, null, listOf(StatementSource("", Effect.ALLOW, listOf("*"))))
            },
            publish.get("bindings")?.strings().orEmpty().map {
                Binding(
                    BindingId("b:$it"),
                    SubjectRef.Role(null, RoleId("editor")),
                    BindingScope(Match.Any, Match.Any),
                    PolicyId(it)
                )
            },
        )
    }

    private val ACTOR = Actor(TenantId("acme"), PrincipalId("alice"))
}

/** The `control` level: grant-analysis scenarios name a `candidate`, publishing scenarios their `publishes`. */
object ControlRunner : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome = when {
        scenario.node.has("publishes") -> StoreRunner.run(scenario)
        scenario.node.has("steps") -> ChangeRunner.run(scenario)
        else -> GrantRunner.run(scenario)
    }
}
