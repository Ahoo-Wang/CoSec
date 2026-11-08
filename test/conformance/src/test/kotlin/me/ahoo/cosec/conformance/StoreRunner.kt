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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.StatementSource
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
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
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
 */
object StoreRunner : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome = runBlocking {
        scenario.node.only("\$comment", "rules", "polarity", "level", "description", "publishes", "expect")
        val memory = InMemoryControlStore()
        val writes = ArrayList<String>()
        var failHead = false
        val store = object : ControlStore by memory {
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

            override suspend fun putReferrers(
                target: SnapshotKey,
                policy: PolicyId,
                expected: SnapshotDigest?,
                referrers: Map<SnapshotKey, Long>,
            ): Boolean {
                currentCoroutineContext()[Hold]?.takeIf { it.written }?.let { hold ->
                    hold.paused.complete(Unit)
                    hold.release.await()
                }
                return memory.putReferrers(target, policy, expected, referrers)
            }
        }
        val revisions = Revisions(store)
        val outcomes = ArrayList<Deferred<String>>()
        val previous = HashMap<SnapshotKey, Head?>()
        var key: SnapshotKey = SnapshotKey.Tenant("acme")
        var held: Pair<Hold, Deferred<String>>? = null
        for (publish in scenario.node.required("publishes").values()) {
            publish.only("document", "policies", "bindings", "base", "failHead", "holdRemovals")
            key = publish.get("document")?.text()?.let(::documentKey) ?: SnapshotKey.Tenant("acme")
            val current = revisions.head(key)
            val base = when (val text = publish.get("base")?.text() ?: "head") {
                "head" -> current
                "stale" -> previous[key]
                else -> throw ScenarioFormatException("A base is head or stale, not $text")
            }
            failHead = publish.get("failHead")?.bool() ?: false
            val candidate = candidate(key, publish)
            val publication = Publication("c${outcomes.size}", ProvenanceKind.PUBLISH, ACTOR)
            val hold = if (publish.get("holdRemovals")?.bool() == true) Hold() else null
            val outcome = async(hold ?: Hold.NONE) { outcomeOf(revisions, key, base, candidate, publication) }
            if (hold != null) {
                // Until it waits to change the back-reference index after its head write, or ends without doing so.
                select { hold.paused.onAwait {}; outcome.onAwait {} }
            } else {
                outcome.await()
                // The held publish finishes now, after the one that followed it.
                held?.let { (it, finishing) -> it.release.complete(Unit).also { finishing.await() } }
                held = null
            }
            failHead = false
            previous[key] = current
            outcomes += outcome
            hold?.let { held = it to outcome }
        }
        held?.let { (it, finishing) -> it.release.complete(Unit).also { finishing.await() } }
        val answered = outcomes.map { it.await() }
        val head = revisions.head(key)
        val parent = head?.document?.provenance?.parent
        Outcome.Stored(
            outcomes = answered,
            revision = head?.revision,
            parent = parent?.revision,
            parentRecord = parent?.let { memory.record(key, SnapshotDigest.parse(it.digest)) != null },
            records = memory.recordDigests(key).size,
            writes = writes,
        )
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

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw ScenarioFormatException("A document is platform or tenant:<id>, not $text")
    }

    private fun candidate(key: SnapshotKey, publish: JsonNode): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
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
