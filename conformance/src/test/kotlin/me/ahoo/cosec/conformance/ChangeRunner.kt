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
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.change.ShadowOptions
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.shadow.InMemoryShadowReports
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant

/**
 * The `control` runner for the change flow (RFC 0007 §6.1–§6.3, §7.2–§7.5): steps over an in-memory control plane,
 * each a proposal or a transition of a named change. Each step records what it left the change in, or
 * `refused <status>`; the outcome also has every change's error checks, the heads at the end, and the reasons of the
 * escalations of each change that has any.
 *
 * ```jsonc
 * "documents": { "platform": {...}, "apps": { "order": {...} }, "tenants": { "acme": {...} } },  // published first
 * "operators": { "alice": { "tenant": "acme", "roles": ["admin"], "policies": [] } },
 * "options":   { "fourEyes": true, "shadow": { "minDuration": "PT15M", "minSamples": 3, "maxDuration": "PT1H" } },
 * "steps": [ { "propose": { "change": "c1", "by": "alice", "document": "tenant:acme", "kind": "BREAK_GLASS",
 *                           "operations": [ { "putPolicy": { ...the document format's policy... } },
 *                                           { "putBinding": { ... } }, { "removePolicy": "id" }, { "removeBinding": "id" } ] } },
 *            { "skipShadow": "c1" }, { "stage": { "change": "c1", "by": "alice" } }, { "shadowed": "c1" },
 *            { "publish": "c1" }, { "abandon": "c1" }, { "rebase": "c1" },
 *            { "approve": { "change": "c1", "by": "bob", "candidate": "own" | "other" } },
 *            { "reject": { "change": "c1", "by": "bob" } }, { "advance": "PT73H" },
 *            { "report": { "change": "c1", "instance": "i1", "counts": { "NARROWED": 2 } } },
 *            { "publish": { "change": "c1", "ack": "summary" | { "NARROWED": 1 } } } ],
 * "expect": { "steps": ["CHECKED", "READY", "PUBLISHED"], "errors": { "c1": [] }, "heads": { "tenant:acme": 1 },
 *             "escalations": { "c1": ["NOT_COVERED"] } }
 * ```
 *
 * A proposal is made on the current head of its document. With `operators`, every step names who takes it (`by`),
 * each is authenticated by a host pipeline and checked through the real pipeline and engine over the current heads
 * (the built-in control-plane policies included); `documents` are published first as `bootstrap` would. Without
 * `operators`, the scenario is about the flow alone: every action is permitted, and a step is taken by `alice` of
 * `acme` on a tenant document and by a platform principal on the others, as the hard rule requires.
 *
 * Shadow windows (RFC 0007 §6.4) close as soon as `shadowed` looks at them unless `options.shadow` sets their bounds;
 * a `report` step writes an instance's cumulative report of a change's current window, as its reporter would.
 */
object ChangeRunner : ScenarioRunner {
    val NOW = Instant.parse("2026-11-01T00:00:00Z")
    val NONE = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
    val ALICE = Operator(Actor(TenantId("acme"), PrincipalId("alice")), NONE)
    val PLATFORM = Operator(Actor(TenantId.PLATFORM, PrincipalId("cosec")), NONE)

    override fun run(scenario: Scenario): Outcome = runBlocking {
        scenario.node.only("\$comment", "rules", "polarity", "level", "description", "documents", "operators", "options", "steps", "expect")
        Run(scenario.node).also { it.start() }.steps()
    }

    /** One scenario's control plane, operators and steps. */
    private class Run(private val node: JsonNode) {
        private val clock = MutableClock(NOW)
        private val store = InMemoryControlStore(clock)
        val revisions = Revisions(store, clock)
        private val engine = ControlEngine(revisions)
        private val authorized = node.has("operators")
        private val operators = node.get("operators")?.fields().orEmpty().entries.associate { (name, operator) ->
            operator.only("tenant", "roles", "policies")
            name to engine.operator(
                TenantId(operator.required("tenant").text()),
                name,
                operator.get("roles")?.strings().orEmpty(),
                operator.get("policies")?.strings().orEmpty(),
            )
        }
        private var next: ChangeId? = null
        private val reports = InMemoryShadowReports(clock)
        private val changes = Changes(
            store,
            revisions,
            { next!! },
            if (authorized) engine.authorizer else ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT },
            clock,
            ChangeOptions(fourEyes = node.get("options")?.get("fourEyes")?.bool() ?: false, shadow = shadowOptions()),
            reports = reports,
            // Break-glass is recorded by a REQUIRED listener before it is created (RFC 0007 §8).
            listeners = ChangeListeners(listOf(RecordingAudit)),
        )
        private val documents = LinkedHashSet<SnapshotKey>()
        private val named = LinkedHashMap<String, ChangeRequest>()

        /** A window closes as soon as it is looked at, unless the scenario sets `options.shadow`. */
        private fun shadowOptions(): ShadowOptions {
            node.get("options")?.only("fourEyes", "shadow")
            val shadow = node.get("options")?.get("shadow") ?: return ShadowOptions(Duration.ZERO, 0, Duration.ZERO)
            shadow.only("minDuration", "minSamples", "maxDuration")
            return ShadowOptions(
                Duration.parse(shadow.required("minDuration").text()),
                shadow.required("minSamples").asLong(),
                Duration.parse(shadow.required("maxDuration").text()),
            )
        }

        suspend fun start() {
            node.get("documents")?.let { bootstrap(revisions, it) }
        }

        suspend fun steps(): Outcome {
            val results = node.required("steps").values().map { step ->
                val (action, argument) = step.fields().entries.singleOrNull()
                    ?: throw ScenarioFormatException("A step is one action: $step")
                if (action == "advance") {
                    clock.now = clock.now.plus(Duration.parse(argument.text()))
                    "advanced"
                } else if (action == "report") {
                    report(argument)
                } else {
                    try {
                        step(action, argument).also { named[it.id.value] = it }.state.name
                    } catch (error: ChangeRejectedException) {
                        "refused ${error.status}"
                    }
                }
            }
            val errors = named.keys.associateWith { id -> changes.get(ChangeId(id)).errors.map { it.check } }
            val escalations = named.keys.associateWith { id -> changes.get(ChangeId(id)).escalations.map { it.reason } }
                .filterValues { it.isNotEmpty() }
            val heads = documents.associate { it.toString() to (revisions.head(it)?.revision ?: 0) }
            return Outcome.Changed(results, errors, heads, escalations)
        }

        private suspend fun step(action: String, argument: JsonNode): ChangeRequest = when (action) {
            "propose" -> propose(argument)
            "shadowed" -> changes.shadowed(ChangeId(argument.text()))
            "approve" -> target(argument, "candidate").let { (id, op, node) ->
                changes.approve(op, id, candidate(id, node))
            }

            else -> transition(action, argument)
        }

        private suspend fun transition(action: String, argument: JsonNode): ChangeRequest {
            val (id, op) = if (action == "publish") target(argument, "ack") else target(argument)
            return when (action) {
                "skipShadow" -> changes.skipShadow(op, id, "scenario")
                "stage" -> changes.stage(op, id)
                "publish" -> changes.publish(op, id, ack(id, argument))
                "abandon" -> changes.abandon(op, id)
                "rebase" -> changes.rebase(op, id)
                "reject" -> changes.reject(op, id, "scenario")
                else -> throw ScenarioFormatException("Unknown step $action")
            }
        }

        /**
         * An instance's cumulative report of a change's window: `{ "change": "c1", "instance": "i1",
         * "counts": { "NARROWED": 2 } }`, as its reporter would write it.
         */
        private suspend fun report(argument: JsonNode): String {
            argument.only("change", "instance", "counts")
            val window = changes.get(ChangeId(argument.required("change").text())).shadow?.window
                ?: throw ScenarioFormatException("A report is of a staged change: $argument")
            val counts = argument.required(
                "counts"
            ).fields().entries.associate { (outcome, count) -> ShadowOutcome.valueOf(outcome) to count.asLong() }
            reports.write(
                ShadowReport(
                    window,
                    argument.required("instance").text(),
                    counts,
                    emptyMap(),
                    emptySet(),
                    clock.now,
                    clock.now
                )
            )
            return "reported"
        }

        /** A publish's `ack`: `"summary"` acknowledges the window's summary, `{ "NARROWED": 1 }` other counts. */
        private suspend fun ack(id: ChangeId, argument: JsonNode): ShadowAck? {
            val ack = if (argument.isString) null else argument.get("ack") ?: return null
            ack ?: return null
            val window = changes.get(id).shadow ?: throw ScenarioFormatException("Only a shadowed change is acknowledged: $argument")
            return if (ack.isString && ack.text() == "summary") {
                ShadowAck(window.window, window.closed?.counts.orEmpty())
            } else {
                ShadowAck(
                    window.window,
                    ack.fields().entries.associate { (outcome, count) -> outcome to count.asLong() }
                )
            }
        }

        private suspend fun propose(argument: JsonNode): ChangeRequest {
            argument.only("change", "by", "document", "operations", "kind")
            val key = documentKey(argument.required("document").text())
            documents += key
            next = ChangeId(argument.required("change").text())
            val head = revisions.head(key)?.revision ?: 0
            val operations = argument.required("operations").values().map { operation(key, it) }
            val kind = argument.get("kind")?.text()?.let(ProvenanceKind::valueOf) ?: ProvenanceKind.PUBLISH
            return changes.propose(by(argument, key), key, head, operations, "scenario", kind)
        }

        private suspend fun candidate(id: ChangeId, node: JsonNode): String = when (val which = node.get("candidate")?.text() ?: "own") {
            "own" -> changes.get(id).candidate.orEmpty()
            "other" -> "0".repeat(DIGEST_LENGTH)
            else -> throw ScenarioFormatException("candidate is own or other, not $which")
        }

        /** Who takes a step: its `by` with operators, otherwise whoever the hard rule lets act on [key]. */
        private fun by(step: JsonNode, key: SnapshotKey): Operator {
            val name = step.get("by")?.text()
            if (authorized != (name != null)) {
                throw ScenarioFormatException(
                    "A step names who takes it exactly when the scenario has operators: $step"
                )
            }
            if (name == null) {
                return if (key is SnapshotKey.Tenant) ALICE else PLATFORM
            }
            return operators[name] ?: throw ScenarioFormatException("No operator $name")
        }

        /** A transition's change and operator: `"c1"`, or `{ "change": "c1", "by": "bob", ... }`. */
        private suspend fun target(argument: JsonNode, vararg fields: String): Triple<ChangeId, Operator, JsonNode> {
            if (!argument.isString) {
                argument.only("change", "by", *fields)
            }
            val id = ChangeId(if (argument.isString) argument.text() else argument.required("change").text())
            return Triple(id, by(argument, changes.get(id).key), argument)
        }
    }

    /** Publishes the scenario's documents as `bootstrap` would (RFC 0007 §5.9): before any change, unchecked. */
    suspend fun bootstrap(revisions: Revisions, documents: JsonNode) {
        documents.only("platform", "apps", "tenants")
        val all = listOfNotNull(documents.get("platform")?.let { SnapshotKey.Platform to it }) +
            documents.get("apps")?.fields().orEmpty().entries.map { (app, it) -> SnapshotKey.App(app) to it } +
            documents.get("tenants")?.fields().orEmpty().entries.map { (tenant, it) -> SnapshotKey.Tenant(tenant) to it }
        for ((key, body) in all) {
            val (kind, owner) = Revisions.ownerOf(key)
            val document = JsonNodeFactory.instance.objectNode()
                .put("schema", PolicyDocumentCodec.SCHEMA).put("revision", 1).put("publishedAt", NOW.toString())
            document.setAll(body as? ObjectNode ?: throw ScenarioFormatException("The $key document is not an object"))
            val decoded = PolicyDocumentCodec.decode(kind, owner, CoSecJsonSerializer.writeValueAsBytes(document))
            revisions.publish(
                key,
                revisions.head(key),
                decoded,
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, PLATFORM.actor)
            )
        }
    }

    fun operation(key: SnapshotKey, node: JsonNode): Operation {
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

    fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw ScenarioFormatException("A document is platform, app:<id> or tenant:<id>, not $text")
    }

    const val DIGEST_LENGTH = 64

    /** A REQUIRED change listener that records everything. */
    private object RecordingAudit : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED

        override suspend fun onChange(event: ChangeEvent) = Unit
    }
}
