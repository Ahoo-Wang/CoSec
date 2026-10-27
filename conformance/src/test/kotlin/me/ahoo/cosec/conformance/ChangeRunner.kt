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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import tools.jackson.databind.JsonNode

/**
 * The `control` runner for the change flow (RFC 0007 §6.1–§6.3): steps over an in-memory control plane, each a
 * proposal or a transition of a named change. Each step records what it left the change in, or `refused <status>`;
 * the outcome also has every change's error checks and the heads at the end.
 *
 * ```jsonc
 * "steps": [ { "propose": { "change": "c1", "document": "tenant:acme",
 *                           "operations": [ { "putPolicy": { ...the document format's policy... } },
 *                                           { "putBinding": { ... } }, { "removePolicy": "id" }, { "removeBinding": "id" } ] } },
 *            { "skipShadow": "c1" }, { "stage": "c1" }, { "shadowed": "c1" }, { "publish": "c1" },
 *            { "abandon": "c1" }, { "rebase": "c1" } ],
 * "expect": { "steps": ["CHECKED", "READY", "PUBLISHED"], "errors": { "c1": [] }, "heads": { "tenant:acme": 1 } }
 * ```
 *
 * A proposal is made on the current head of its document.
 */
object ChangeRunner : ScenarioRunner {
    private val author = Actor(TenantId("acme"), PrincipalId("alice"))

    override fun run(scenario: Scenario): Outcome = runBlocking {
        scenario.node.only("\$comment", "rules", "polarity", "level", "description", "steps", "expect")
        val store = InMemoryControlStore()
        val revisions = Revisions(store)
        var next: ChangeId? = null
        val changes = Changes(store, revisions, { next!! })
        val results = ArrayList<String>()
        val documents = LinkedHashSet<SnapshotKey>()
        val named = LinkedHashMap<String, ChangeRequest>()
        for (step in scenario.node.required("steps").values()) {
            val (action, argument) = step.fields().entries.singleOrNull()
                ?: throw ScenarioFormatException("A step is one action: $step")
            results += try {
                val change = when (action) {
                    "propose" -> {
                        argument.only("change", "document", "operations")
                        val key = documentKey(argument.required("document").text())
                        documents += key
                        next = ChangeId(argument.required("change").text())
                        val head = revisions.head(key)?.revision ?: 0
                        changes.propose(author, key, head, argument.required("operations").values().map { operation(key, it) }, "scenario")
                    }

                    "skipShadow" -> changes.skipShadow(ChangeId(argument.text()), "scenario")
                    "stage" -> changes.stage(ChangeId(argument.text()))
                    "shadowed" -> changes.shadowed(ChangeId(argument.text()))
                    "publish" -> changes.publish(ChangeId(argument.text()))
                    "abandon" -> changes.abandon(ChangeId(argument.text()))
                    "rebase" -> changes.rebase(ChangeId(argument.text()))
                    else -> throw ScenarioFormatException("Unknown step $action")
                }
                named[change.id.value] = change
                change.state.name
            } catch (error: ChangeRejectedException) {
                "refused ${error.status}"
            }
        }
        val errors = named.keys.associateWith { id -> changes.get(ChangeId(id)).errors.map { it.check } }
        val heads = documents.associate { it.toString() to (revisions.head(it)?.revision ?: 0) }
        Outcome.Changed(results, errors, heads)
    }

    private fun operation(key: SnapshotKey, node: JsonNode): Operation {
        val (kind, owner) = Revisions.ownerOf(key)
        val (name, value) = node.fields().entries.singleOrNull() ?: throw ScenarioFormatException("An operation is one entry: $node")
        return when (name) {
            "putPolicy" -> Operation.PutPolicy(PolicyDocumentCodec.decodePolicy(value))
            "putBinding" -> Operation.PutBinding(PolicyDocumentCodec.decodeBinding(value, kind, owner))
            "removePolicy" -> Operation.RemovePolicy(PolicyId(value.text()))
            "removeBinding" -> Operation.RemoveBinding(BindingId(value.text()))
            else -> throw ScenarioFormatException("Unknown operation $name")
        }
    }

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw ScenarioFormatException("A document is platform, app:<id> or tenant:<id>, not $text")
    }
}
