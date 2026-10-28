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
import me.ahoo.cosec.control.api.DecisionView
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.api.SimulatedRequest
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeAuthority
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
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.shadow.InMemoryShadowReports
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
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
 * "operators": { "alice": { "tenant": "acme", "roles": ["admin"], "policies": [], "attributes": { "level": 3 } } },
 * "options":   { "fourEyes": true, "shadow": { "minDuration": "PT15M", "minSamples": 3, "maxDuration": "PT1H" },
 *                "providers": [ { "path": "ext.geo.country", "type": "string" } ] },   // the host's AttributeProviders
 * "steps": [ { "propose": { "change": "c1", "by": "alice", "document": "tenant:acme", "kind": "BREAK_GLASS",
 *                           "operations": [ { "putPolicy": { ...the document format's policy... } },
 *                                           { "putBinding": { ... } }, { "removePolicy": "id" }, { "removeBinding": "id" } ] } },
 *            { "skipShadow": "c1" }, { "stage": { "change": "c1", "by": "alice" } }, { "shadowed": "c1" },
 *            { "publish": "c1" }, { "abandon": "c1" }, { "rebase": "c1" },
 *            { "approve": { "change": "c1", "by": "bob", "candidate": "own" | "other" } },
 *            { "reject": { "change": "c1", "by": "bob" } }, { "advance": "PT73H" },
 *            { "report": { "change": "c1", "instance": "i1", "counts": { "NARROWED": 2 } } },
 *            { "publish": { "change": "c1", "ack": "summary" | { "NARROWED": 1 } } },
 *            { "rollback": { "change": "c2", "by": "walt", "document": "platform", "revision": 1 } },
 *            { "outage": true },   // every check is indeterminate until { "outage": false }
 *            { "vanish": "i1" },   // i1 is taken off the reporters of every window, its report left
 *            { "simulate": { "by": "alice", "request": { ... }, "candidate": "c1" } },
 *            { "apply": { "change": "c3", "by": "walt", "document": "platform", "mode": "REPLACE", "desired": {...} } },
 *            { "rateLimits": "platform" } ],   // the rule ids of the document's head, in order
 *  operations also take { "putRateLimit": { ...a rule... } } and { "removeRateLimit": "id" }
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
            operator.only("tenant", "roles", "policies", "attributes")
            name to engine.operator(
                TenantId(operator.required("tenant").text()),
                name,
                operator.get("roles")?.strings().orEmpty(),
                operator.get("policies")?.strings().orEmpty(),
                operator.get("attributes")?.let(::attributes).orEmpty(),
            )
        }
        private var next: ChangeId? = null
        private val reports = InMemoryShadowReports(clock)

        /** Instances a `vanish` step took off the window's reporters, as an instance that can write them could. */
        private val vanished = HashSet<String>()
        private val reader = object : ShadowReportReader {
            override suspend fun reports(window: String) = reports.reports(window).filter { it.instance !in vanished }

            override suspend fun statuses() = reports.statuses()
        }
        private val changes = Changes(
            store,
            revisions,
            { next!! },
            if (authorized) engine.authorizer else ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT },
            clock,
            ChangeOptions(fourEyes = node.get("options")?.get("fourEyes")?.bool() ?: false, shadow = shadowOptions()),
            attributes = providers(),
            reports = reader,
            // Break-glass is recorded by a REQUIRED listener before it is created (RFC 0007 §8).
            listeners = ChangeListeners(listOf(RecordingAudit)),
        )
        private val documents = LinkedHashSet<SnapshotKey>()
        private val named = LinkedHashMap<String, ChangeRequest>()
        private val plane = DefaultControlPlane(
            changes,
            revisions,
            ChangeAuthority(
                if (authorized) engine.authorizer else ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT }
            ),
        )

        /** A window closes as soon as it is looked at, unless the scenario sets `options.shadow`. */
        private fun shadowOptions(): ShadowOptions {
            node.get("options")?.only("fourEyes", "shadow", "providers")
            val shadow = node.get("options")?.get("shadow") ?: return ShadowOptions(Duration.ZERO, 0, Duration.ZERO)
            shadow.only("minDuration", "minSamples", "maxDuration")
            return ShadowOptions(
                Duration.parse(shadow.required("minDuration").text()),
                shadow.required("minSamples").asLong(),
                Duration.parse(shadow.required("maxDuration").text()),
            )
        }

        /** The attributes the host's providers declare, which candidates compile against with the platform's. */
        private fun providers(): AttributeCatalog = AttributeCatalog.of(
            node.get("options")?.get("providers")?.values().orEmpty().map {
                it.only("path", "type")
                val type = PolicyDocumentCodec.ATTRIBUTE_TYPES[it.required("type").text()]
                    ?: throw ScenarioFormatException("Unknown attribute type in $it")
                Attribute.external(it.required("path").text(), type)
            },
        )

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
                } else if (action == "vanish") {
                    vanished += argument.text()
                    "vanished"
                } else if (action == "outage") {
                    engine.outage = argument.bool()
                    if (engine.outage) "down" else "up"
                } else if (action == "report") {
                    report(argument)
                } else if (action == "simulate") {
                    simulate(argument)
                } else if (action == "rateLimits") {
                    val head = revisions.head(documentKey(argument.text()))
                    "rateLimits " + head?.document?.rateLimits.orEmpty().joinToString(",", "[", "]") { it.id }
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
            "apply" -> apply(argument)
            "rollback" -> rollback(argument)
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

        /**
         * The control plane's `simulate` (RFC 0007 §10.3): `{ "by": "alice", "request": { "tenant": "acme", "principal":
         * "bob", "roles": [], "app": "", "space": "", "action": "order:read", "resource": "order/1" }, "candidate": "c1" }`.
         * Its result is the decision, then `by <policy>` when the caller may see it, then `; candidate <decision>`.
         */
        private suspend fun simulate(argument: JsonNode): String = try {
            argument.only("by", "request", "candidate")
            val request = argument.required("request").also {
                it.only("tenant", "principal", "roles", "app", "space", "action", "resource", "headers", "requestAttributes")
            }
            val action = request.required("action").text().split(':', limit = 2)
            val resource = request.required("resource").text().split('/', limit = 2)
            val tenant = TenantId(request.required("tenant").text())
            val simulated = SimulatedRequest(
                tenant = tenant,
                principal = request.get("principal")?.text(),
                roles = request.get("roles")?.strings().orEmpty(),
                app = request.get("app")?.text().orEmpty(),
                space = request.get("space")?.text().orEmpty(),
                action = Action(action[0], action[1]),
                resource = Resource(resource[0], resource.getOrElse(1) { "" }, emptyMap()),
                time = clock.now.toEpochMilli(),
                headers = request.get("headers")?.fields().orEmpty().mapValues { (_, value) -> value.strings() },
                requestAttributes = request.get("requestAttributes")?.fields().orEmpty()
                    .mapValues { (_, value) -> value.text() },
            )
            val result = plane.simulate(
                by(argument, SnapshotKey.Tenant(tenant.value)),
                simulated,
                argument.get("candidate")?.text()?.let(::ChangeId)
            )
            fun shown(decision: DecisionView) = decision.decision + (decision.by?.let { " by ${it.policy}" } ?: "")
            shown(result.enforced) + (result.candidate?.let { "; candidate ${shown(it)}" } ?: "")
        } catch (error: ChangeRejectedException) {
            "refused ${error.status}"
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

        /** `:apply` (RFC 0007 §6.7): the change that makes the document's head into `desired`, by `mode`. */
        private suspend fun apply(argument: JsonNode): ChangeRequest {
            argument.only("change", "by", "document", "desired", "mode")
            val key = documentKey(argument.required("document").text())
            documents += key
            next = ChangeId(argument.required("change").text())
            val desired = decode(key, argument.required("desired"), revisions.head(key)?.revision ?: 0)
            val mode = ApplyMode.valueOf(argument.required("mode").text())
            return plane.apply(by(argument, key), key, desired, mode, "scenario")
        }

        /** `{ "change": "c2", "by": "walt", "document": "platform", "revision": 1 }`: a rollback (RFC 0007 §5.6). */
        private suspend fun rollback(argument: JsonNode): ChangeRequest {
            argument.only("change", "by", "document", "revision")
            val key = documentKey(argument.required("document").text())
            documents += key
            next = ChangeId(argument.required("change").text())
            return changes.rollback(by(argument, key), key, argument.required("revision").asLong(), "scenario")
        }

        private suspend fun candidate(id: ChangeId, node: JsonNode): String = when (val which = node.get("candidate")?.text() ?: "own") {
            "own" -> store.change(id)?.value?.candidate.orEmpty()
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
            // A change that does not exist is still named, so that the step answers for it as for any other.
            return Triple(id, by(argument, store.change(id)?.value?.key ?: SnapshotKey.Platform), argument)
        }
    }

    /** Publishes the scenario's documents as `bootstrap` would (RFC 0007 §5.9): before any change, unchecked. */
    suspend fun bootstrap(revisions: Revisions, documents: JsonNode) {
        documents.only("platform", "apps", "tenants")
        val all = listOfNotNull(documents.get("platform")?.let { SnapshotKey.Platform to it }) +
            documents.get("apps")?.fields().orEmpty().entries.map { (app, it) -> SnapshotKey.App(app) to it } +
            documents.get("tenants")?.fields().orEmpty().entries.map { (tenant, it) -> SnapshotKey.Tenant(tenant) to it }
        for ((key, body) in all) {
            val decoded = decode(key, body, 0)
            revisions.publish(
                key,
                revisions.head(key),
                decoded,
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, PLATFORM.actor)
            )
        }
    }

    /** A scenario's document body as [key]'s document, one revision past [head]. */
    fun decode(key: SnapshotKey, body: JsonNode, head: Long): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
        val document = JsonNodeFactory.instance.objectNode()
            .put("schema", PolicyDocumentCodec.SCHEMA).put("revision", head + 1).put("publishedAt", NOW.toString())
        document.setAll(body as? ObjectNode ?: throw ScenarioFormatException("The $key document is not an object"))
        return PolicyDocumentCodec.decode(kind, owner, CoSecJsonSerializer.writeValueAsBytes(document))
    }

    /** An operator's token attributes: `{ "level": 3, "oncall": true, "teams": ["red"] }`, as a token's claims read. */
    fun attributes(node: JsonNode): Map<String, Any> {
        if (!node.isObject) {
            throw ScenarioFormatException("attributes is an object: $node")
        }
        return node.fields().entries.associate { (name, value) -> name to value(value) }
    }

    private fun value(node: JsonNode): Any = when {
        node.isString -> node.text()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node.isArray -> node.values().map(::value)
        node.isObject -> attributes(node)
        else -> throw ScenarioFormatException("An attribute is a string, number, boolean, array or object: $node")
    }

    fun operation(key: SnapshotKey, node: JsonNode): Operation {
        val (kind, owner) = Revisions.ownerOf(key)
        val (name, value) = node.fields().entries.singleOrNull() ?: throw ScenarioFormatException("An operation is one entry: $node")
        return when (name) {
            "putPolicy" -> Operation.PutPolicy(PolicyDocumentCodec.decodePolicy(value))
            "putBinding" -> Operation.PutBinding(PolicyDocumentCodec.decodeBinding(value, kind, owner))
            "removePolicy" -> Operation.RemovePolicy(PolicyId(value.text()))
            "removeBinding" -> Operation.RemoveBinding(BindingId(value.text()))
            "putRateLimit" -> Operation.PutRateLimit(PolicyDocumentCodec.decodeRateLimit(value))
            "removeRateLimit" -> Operation.RemoveRateLimit(value.text())
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
