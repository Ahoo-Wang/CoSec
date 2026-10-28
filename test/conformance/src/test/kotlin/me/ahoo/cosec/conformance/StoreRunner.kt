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

package me.ahoo.cosec.conformance

import kotlinx.coroutines.runBlocking
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
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * The `control` runner for publishing (RFC 0007 §5.4, §5.8): a sequence of publishes to an in-memory `ControlStore`,
 * each of one document (`"document"`: `platform` or `tenant:<id>`, by default `tenant:acme`) with its own policies and
 * bindings of role `editor` to the named policies, on the current head or on the head before the previous publish of
 * that document (`"base": "stale"`), optionally with its head write failing (`"failHead": true`). The outcome is what
 * each publish answered, the head of the last publish's document afterwards, the records it holds, and the order of
 * the writes.
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
                return memory.putHead(key, expected, document)
            }
        }
        val revisions = Revisions(store)
        val outcomes = ArrayList<String>()
        val previous = HashMap<SnapshotKey, Head?>()
        var key: SnapshotKey = SnapshotKey.Tenant("acme")
        for (publish in scenario.node.required("publishes").values()) {
            publish.only("document", "policies", "bindings", "base", "failHead")
            key = publish.get("document")?.text()?.let(::documentKey) ?: SnapshotKey.Tenant("acme")
            val current = revisions.head(key)
            val base = when (val text = publish.get("base")?.text() ?: "head") {
                "head" -> current
                "stale" -> previous[key]
                else -> throw ScenarioFormatException("A base is head or stale, not $text")
            }
            failHead = publish.get("failHead")?.bool() ?: false
            outcomes += try {
                when (revisions.publish(key, base, candidate(key, publish), Publication("c${outcomes.size}", ProvenanceKind.PUBLISH, ACTOR))) {
                    is PublishOutcome.Published -> "Published"
                    is PublishOutcome.Conflicted -> "Conflicted"
                    is PublishOutcome.Referenced -> "Referenced"
                }
            } catch (_: IllegalStateException) {
                "Failed"
            }
            failHead = false
            previous[key] = current
        }
        val head = revisions.head(key)
        val parent = head?.document?.provenance?.parent
        Outcome.Stored(
            outcomes = outcomes,
            revision = head?.revision,
            parent = parent?.revision,
            parentRecord = parent?.let { memory.record(key, SnapshotDigest(hex(it.digest))) != null },
            records = memory.recordDigests(key).size,
            writes = writes,
        )
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

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) {
        text.substring(2 * it, 2 * it + 2).toInt(16).toByte()
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
