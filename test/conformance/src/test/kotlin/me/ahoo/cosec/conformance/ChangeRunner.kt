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

package me.ahoo.cosec.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ChangeAnswer
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlLockout
import me.ahoo.cosec.control.api.DecisionView
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.api.EntryChanges
import me.ahoo.cosec.control.api.SimulatedRequest
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.Approval
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.lifecycle.CandidateCompile
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.lifecycle.ShadowOptions
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.shadow.StagedDocuments
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.StatusReadings
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
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
 * "operators": { "alice": { "tenant": "acme", "roles": ["admin"], "policies": [], "attributes": { "level": 3 } },
 *                "kim":  { "tenant": "(platform)", "credential": "api-key" },           // authenticated by an API key
 *                "sam":  { "tenant": "(platform)", "credential": "service" },           // a service's own identity
 *                "pia":  { "tenant": "(platform)", "credential": { "token": "t1", "issuedAt": "2026-11-01T00:00:00Z" } },
 *                "nia":  { "tenant": "(platform)", "credential": "none" },  // a credential without an id; by default,
 *                                                                           // token "token-<name>", issued at no time
 *                "dave": { "tenant": "acme", "roles": ["approver"], "provider": true } },  // the provider says its roles
 * "options":   { "fourEyes": true, "lockout": true (the UC-6 check, off by default), "shadow": { "minDuration": "PT15M", "minSamples": 3, "maxDuration": "PT1H" },
 *                "providers": [ { "path": "ext.geo.country", "type": "string" } ] },   // the host's AttributeProviders
 * "steps": [ { "propose": { "change": "c1", "by": "alice", "document": "tenant:acme", "kind": "BREAK_GLASS",
 *                           "operations": [ { "putPolicy": { ...the document format's policy... } },
 *                                           { "putBinding": { ... } }, { "removePolicy": "id" }, { "removeBinding": "id" } ] } },
 *            { "skipShadow": "c1" }, { "stage": { "change": "c1", "by": "alice" } }, { "shadowed": "c1" },
 *            { "publish": "c1" }, { "abandon": "c1" }, { "rebase": "c1" },
 *            { "approve": { "change": "c1", "by": "bob", "candidate": "own" | "other" } },
 *            { "approveAsBefore": { "change": "c1", "by": "bob" } },  // READY, approved as an older instance stored it
 *            { "reject": { "change": "c1", "by": "bob" } }, { "advance": "PT73H" },
 *            { "report": { "change": "c1", "instance": "i1", "counts": { "NARROWED": 2 } } },
 *            { "publish": { "change": "c1", "ack": "summary" | { "NARROWED": 1 } } },
 *            { "publish": { "change": "c1", "by": "walt", "lockout": true } },   // acknowledges a lock-out (UC-6)
 *            { "rollback": { "change": "c2", "by": "walt", "document": "platform", "revision": 1 } },
 *            { "outage": true },   // every check is indeterminate until { "outage": false }
 *            { "changes": { "by": "alice", "document": "tenant:acme" } },  // GET /changes (CTL-55): `changes c1,c2`,
 *                                              // `changes none` or `refused 503`; or with "state": "CHECKED"
 *            { "oldOpenList": true },   // the list of the format before 8.0.0-beta.5 is there: the sets are not kept (CTL-54)
 *            { "storeDown": true }, // every operation of the control store fails until false (CTL-52): a step it
 *                                   // stops is `unavailable`, never an empty answer
 *            { "vanish": "i1" },   // i1 is taken off the reporters of every window, its report left
 *            { "audited": "c1" },  // the kinds of the change events of c1 so far, in the order the listener
 *                                  // received them, which is the order they were written (CTL-63, CTL-66):
 *                                  // `audited CHECKED,SHADOW_SKIPPED`
 *            { "documents": { "by": "plato", "kind": "tenant" } },  // GET /documents?kind= (CTL-68):
 *                                  // `documents tenant:acme,tenant:globex`, `documents none` or `refused <status>`
 *            { "read": { "by": "lena", "document": "tenant:acme" } },  // GET /documents/{key}: `read` or `refused <status>`
 *            { "origin": { "by": "alice", "change": "c1" } },  // the author's origin in the change's answer as
 *                                  // `by` reads it (CTL-70): `origin asserted false via oidc; members
 *                                  // asserted,via.credential,via.method,via.request`, `origin none` or `refused <status>`
 *            { "answer": "c1" },   // the members of the change's answer (CTL-61): `members a,b,...`, then
 *                                  // `; shadow a,b` and `; approval a,b` when it holds them
 *            { "status": { "instance": "i1", "documents": { "tenant:acme": 2 } } },  // an instance's status now
 *            { "status": { "instance": "i1", "documents": {}, "acknowledges": ["c1"] } },  // CTL-10: it lists c1's window
 *                                              // (without "acknowledges" it sends no windows, as an older version;
 *                                              // with "shadows": false it does not shadow, and is not waited for;
 *                                              // with "unreadable": true its status does not decode, CTL-73)
 *            { "acknowledgement": "c1" },      // CTL-10: `acknowledged 1 waiting i2`, or `waiting none`, as the answer
 *            { "propagation": { "document": "tenant:acme", "revision": 2 } },        // CTL-62:
 *                                              // `installed 1 holding 2 recent 3`
 *            { "viaFacade": { "by": "alice", "change": "c1", "propose": { ...the body of POST /changes... } } },
 *            { "viaFacade": { "by": "plato", "document": "tenant:acme" } },  // CTL-65: the facade an application
 *                                              // calls, as by's request: the change's state, `read`, or `refused <status>`
 *            { "simulate": { "by": "alice", "request": { ... }, "candidate": "c1" } },
 *            { "explain": { "by": "alice", "request": { ... }, "at": { "platform": 1, "app": 1, "tenant": 1 } } },
 *            { "apply": { "change": "c3", "by": "walt", "document": "platform", "mode": "REPLACE", "desired": {...} } },
 *                                              // "revision": the header's, the revision the file was read at (default: the head's)
 *            { "rateLimits": "platform" },     // the rule ids of the document's head, in order
 *            { "staged": "tenant:acme" },      // the change whose candidate the document stages: `staged c2`, `staged none`
 *            { "listed": "tenant:acme" },      // the changes whose windows the staged list names for it (CTL-46):
 *                                              // `listed c1,c2`, `listed none`; the list is kept from the start
 *            { "samples": "tenant:acme" },     // whether a data-plane instance, having read the staged list, reads
 *                                              // the document's candidate and samples its requests (CTL-47):
 *                                              // `sampled` or `not sampled`
 *            { "revokeBreakGlass": { "change": "c2", "by": "rita", "binding": "glass" } },  // DELETE /break-glass/glass
 *            { "revoke": "t1" },               // that token is revoked; { "revokeTokens": "pia" }: every token of pia's
 *            { "provide": { "operator": "dave", "roles": [] } },   // what the provider answers for dave from now on
 *            { "unaudited": true },            // later steps run on an instance without a REQUIRED change listener
 *            { "late": { "abandon": "c1" } } ], // the step runs until it withdraws a candidate, which waits until the
 *                                              // next step has run (#1227); its result is the step's
 *  operations also take { "putRateLimit": { ...a rule... } }, { "removeRateLimit": "id" }, { "setBoundary": "id" | null },
 *  { "putRole": { ...a role... } }, { "removeRole": "id" } and { "putCatalog": { ...a catalog... } | null }
 *  { "diff": { "document": "tenant:acme", "from": 1, "to": 2 } } is the diff of two revisions, as a line of what it lists:
 *  `diff policies=[+p] bindings=[+b,-c] roles=[~r] sections=[catalog]`
 * "expect": { "steps": ["CHECKED", "READY", "PUBLISHED"], "errors": { "c1": [] }, "heads": { "tenant:acme": 1 },
 *             "escalations": { "c1": ["NOT_COVERED"] }, "warnings": { "c1": ["OPERATION $.bindings"] } }
 * ```
 *
 * A proposal is made on the current head of its document. With `operators`, every step names who takes it (`by`),
 * each is authenticated by a host pipeline and checked through the real pipeline and engine over the current heads
 * (the built-in control-plane policies included); `documents` are published first as `bootstrap` would. Without
 * `operators`, the scenario is about the flow alone: every action is permitted, and every step is taken by a platform
 * principal, the trust root of every document (RC1-10).
 *
 * Shadow windows (RFC 0007 §6.4) close as soon as `shadowed` looks at them unless `options.shadow` sets their bounds;
 * a `report` step writes an instance's cumulative report of a change's current window, as its reporter would.
 */
/** A `late` step: its candidate withdrawals wait for [release] (#1227). */
private class Late : kotlin.coroutines.AbstractCoroutineContextElement(Late) {
    val paused = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    companion object Key : kotlin.coroutines.CoroutineContext.Key<Late>
}

object ChangeRunner : ScenarioRunner {
    val NOW = Instant.parse("2026-11-01T00:00:00Z")
    val NONE = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
    val PLATFORM = Operator(Actor(TenantId.PLATFORM, PrincipalId("cosec")), NONE)

    override fun run(scenario: Scenario): Outcome = runBlocking {
        scenario.node.only("\$comment", "rules", "polarity", "level", "description", "documents", "operators", "options", "steps", "expect")
        Run(scenario.node).also { it.start() }.steps()
    }

    /** One scenario's control plane, operators and steps. */
    private class Run(private val node: JsonNode) {
        private val clock = MutableClock(NOW)

        // Kept, as the control plane keeps it from its start (CTL-46).
        private val memory = InMemoryControlStore(clock).also {
            runBlocking {
                it.keepStagedList()
                it.keepOpenChanges()
            }
        }

        /** While true, every operation of the store throws [ControlStoreException] (CTL-52). */
        @Volatile
        private var storeDown = false

        /** The store, whose candidate withdrawals inside a `late` step wait for its [Late.release]. */
        private val store = down(object : ControlStore by memory {
            override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean {
                currentCoroutineContext()[Late]?.let { late ->
                    late.paused.complete(Unit)
                    late.release.await()
                }
                return memory.withdrawCandidate(key, window)
            }
        })

        /** [store] whose every operation throws while [storeDown]: a proxy, so no operation of the port is left out. */
        private fun down(store: ControlStore): ControlStore = java.lang.reflect.Proxy.newProxyInstance(
            ControlStore::class.java.classLoader,
            arrayOf(ControlStore::class.java),
        ) { _, method, args ->
            if (storeDown && method.declaringClass != Any::class.java) {
                throw ControlStoreException("${method.name}: the store is down")
            }
            try {
                method.invoke(store, *args.orEmpty())
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as ControlStore

        val revisions = Revisions(store, clock)

        /** The roles the membership provider answers, by operator; a `provide` step changes them. */
        private val provided = node.get("operators")?.fields().orEmpty().entries
            .filter { (_, operator) -> operator.get("provider")?.bool() == true }
            .associateTo(java.util.concurrent.ConcurrentHashMap()) { (name, operator) ->
                name to operator.get("roles")?.strings().orEmpty()
            }
        private val provider = MembershipProvider { _, principal ->
            provided[principal.value]?.let { roles ->
                MembershipAnswer.Known(
                    MembershipClaims.parse(roles, emptyList()).let {
                        Memberships(Memberships.Source.PROVIDER, it.roles, it.attachments)
                    }
                )
            } ?: MembershipAnswer.Unknown
        }
        private val engine = ControlEngine(revisions, provider)
        private val authorized = node.has("operators")

        /** Each operator's request as the host pipeline permitted it: what the control API's routes take. */
        private val securities = node.get("operators")?.fields().orEmpty().entries.associate { (name, operator) ->
            operator.only("tenant", "roles", "policies", "attributes", "credential", "provider")
            val credential = operator.get("credential")
            name to engine.security(
                TenantId(operator.required("tenant").text()),
                name,
                operator.get("roles")?.strings().orEmpty(),
                operator.get("policies")?.strings().orEmpty(),
                operator.get("attributes")?.let(::attributes).orEmpty(),
                // By default a token of its own, as an approval records it.
                credential = credential?.let(::credential)
                    ?: Identity.Credential(Identity.Method("oidc"), "token-$name", null).takeIf { credential == null },
                issuedAt = credential?.get("issuedAt")?.let { Instant.parse(it.text()) },
                provided = operator.get("provider")?.bool() == true,
            )
        }
        private val operators = securities.mapValues { (_, security) -> Operator.of(security)!! }

        /** Revoked token ids, and the instant before which an operator's tokens are revoked. */
        private val revokedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val revokedBefore = java.util.concurrent.ConcurrentHashMap<String, Instant>()
        private val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = tokenId in revokedTokens

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                revokedBefore[principal.value]
        }

        /**
         * `"api-key"`, `"service"` (a service's own identity, IDN-34), `"none"` (a credential that names no id), or
         * `{ "token": "t1", "issuedAt": ... }`: a token
         * CoSec's verifier did not issue, by its id.
         */
        private fun credential(node: JsonNode): Identity.Credential? = if (node.isString) {
            when (node.text()) {
                "api-key" -> Identity.Credential(Identity.Method.API_KEY, "key", null)
                "service" -> Identity.Credential(Identity.Method.SERVICE, "service:svc", null)
                "none" -> null
                else -> throw ScenarioFormatException("Unknown credential $node")
            }
        } else {
            node.only("token", "issuedAt")
            Identity.Credential(Identity.Method("oidc"), node.required("token").text(), null)
        }
        private var next: ChangeId? = null
        private val reports = InMemoryShadowReports(clock)

        /** Instances a `vanish` step took off the window's reporters, as an instance that can write them could. */
        private val vanished = HashSet<String>()

        /** Instances a `status` step with `"unreadable": true` wrote a status for that does not decode (`CTL-73`). */
        private val unreadable = HashSet<String>()
        private val reader = object : ShadowReportReader {
            override suspend fun reports(window: String) = reports.reports(window).filter { it.instance !in vanished }

            override suspend fun statuses() = reports.statuses()

            override suspend fun residents(instances: Set<String>) = reports.residents(instances)

            override suspend fun statusReadings() =
                StatusReadings(reports.statuses().filter { it.instance !in unreadable }, unreadable.toSet())
        }
        private val authorizer =
            if (authorized) engine.authorizer else ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT }
        private val options =
            ChangeOptions(fourEyes = node.get("options")?.get("fourEyes")?.bool() ?: false, shadow = shadowOptions())

        // Break-glass is recorded by a REQUIRED listener before it is created (RFC 0007 §8).
        /** Every change event of the scenario, as a REQUIRED listener records them (`audited`, CTL-63). */
        private val audit = RecordingAudit()
        private val auditing = ChangeListeners(listOf(audit))

        private fun changes(listeners: ChangeListeners = auditing) = Changes(
            store,
            revisions,
            { next!! },
            authorizer,
            clock,
            options,
            compile = CandidateCompile(providers()),
            reports = reader,
            listeners = listeners,
            revocations = revocations,
            authority = ChangeAuthority(authorizer, options, revocations = revocations, revocationSkew = Duration.ZERO),
            // UC-6: the lock-out check, as a host wires it, when the scenario asks for it.
            lockout = if (node.get("options")?.get("lockout")?.bool() == true) {
                ControlLockout(
                    revisions
                )
            } else {
                LockoutCheck.UNCHECKED
            },
        )

        private var changes = changes()
        private val documents = LinkedHashSet<SnapshotKey>()
        private val named = LinkedHashMap<String, ChangeRequest>()
        private val plane = DefaultControlPlane(
            changes,
            revisions,
            ChangeAuthority(
                if (authorized) engine.authorizer else ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT },
                revocations = revocations,
            ),
        )

        /** The facade an application's own code calls (`CTL-65`), over this plane's routes. */
        private val facade = LocalControlService(plane, IdentityGate(clock = clock))

        /** A window closes as soon as it is looked at, unless the scenario sets `options.shadow`. */
        private fun shadowOptions(): ShadowOptions {
            node.get("options")?.only("fourEyes", "shadow", "providers", "lockout")
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

        suspend fun steps(): Outcome = coroutineScope {
            val results = ArrayList<String>()
            var late: Pair<Late, kotlinx.coroutines.Deferred<String>>? = null
            for (step in node.required("steps").values()) {
                val (action, argument) = step.fields().entries.singleOrNull()
                    ?: throw ScenarioFormatException("A step is one action: $step")
                if (action == "late") {
                    if (late != null) throw ScenarioFormatException("A late step follows another: $step")
                    val (inner, innerArgument) = argument.fields().entries.singleOrNull()
                        ?: throw ScenarioFormatException("A late step is one action: $step")
                    val hold = Late()
                    val running = async(hold) { result(inner, innerArgument) }
                    // Until it waits to withdraw a candidate, or ends without one.
                    select {
                        hold.paused.onAwait {}
                        running.onAwait {}
                    }
                    results += ""
                    late = hold to running
                    continue
                }
                results += result(action, argument)
                late?.let { (hold, running) ->
                    hold.release.complete(Unit)
                    results[results.size - 2] = running.await()
                }
                late = null
            }
            late?.let { (hold, running) ->
                hold.release.complete(Unit)
                results[results.size - 1] = running.await()
            }
            outcome(results)
        }

        private suspend fun outcome(results: List<String>): Outcome {
            val errors = named.keys.associateWith { id -> changes.get(ChangeId(id)).errors.map { it.check } }
            val escalations = named.keys.associateWith { id ->
                changes.get(
                    ChangeId(id)
                ).escalations.map { it.reason.name }
            }
                .filterValues { it.isNotEmpty() }
            val warnings = named.keys.associateWith { id ->
                changes.get(
                    ChangeId(id)
                ).findings.filter { it.severity == Severity.WARNING }.map { "${it.check} ${it.path}" }
            }.filterValues { it.isNotEmpty() }
            val heads = documents.associate { it.toString() to (revisions.head(it)?.revision ?: 0) }
            return Outcome.Changed(results, errors, heads, escalations, warnings)
        }

        /** One step's result, as `expect.steps` writes it. */
        private suspend fun result(action: String, argument: JsonNode): String = when (action) {
            "advance", "vanish", "outage", "storeDown", "oldOpenList" -> environment(action, argument)
            "changes" -> listing(argument)
            "report" -> report(argument)
            in QUERIES -> query(action, argument)
            in CREDENTIAL_STEPS -> credentials(action, argument)
            "revokeBreakGlass" -> revoke(argument)
            in READS -> read(action, argument)
            else -> try {
                step(action, argument).also { named[it.id.value] = it }.state.name
            } catch (error: ChangeRejectedException) {
                refused(error)
            } catch (_: SnapshotFormatException) {
                "malformed"
            } catch (_: ControlStoreException) {
                "unavailable"
            }
        }

        /** What a step reads of the documents, without changing them. */
        private suspend fun read(action: String, argument: JsonNode): String = when (action) {
            "diff" -> diff(argument)
            "answer", "status", "propagation", "acknowledgement" -> answers(action, argument)
            "audited" -> audited(argument)
            "documents" -> documents(argument)
            "origin" -> origin(argument)
            "read" -> readDocument(argument)
            "staged" -> "staged " + (memory.candidate(documentKey(argument.text()))?.window?.substringBefore('@') ?: "none")
            "samples" -> StagedDocuments(memory).let { instance ->
                instance.refresh()
                if (instance.mayStage(documentKey(argument.text()))) "sampled" else "not sampled"
            }
            "listed" -> "listed " + (
                memory.staged()!!.windows[documentKey(argument.text())].orEmpty()
                    .map { it.substringBefore('@') }.sorted().joinToString(",").ifEmpty { "none" }
                )
            else -> {
                val head = revisions.head(documentKey(argument.text()))
                "rateLimits " + head?.document?.rateLimits.orEmpty().joinToString(",", "[", "]") { it.id }
            }
        }

        private suspend fun diff(argument: JsonNode): String {
            argument.only("document", "from", "to")
            val plane = DefaultControlPlane(
                changes,
                revisions,
                ChangeAuthority(
                    ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT },
                    revocations = RevocationReader.NONE
                ),
            )
            val found = plane.diff(
                PLATFORM,
                documentKey(argument.required("document").text()),
                argument.required("from").longValue(),
                argument.required("to").longValue(),
            )
            fun line(entries: EntryChanges): String = (
                entries.added.map { "+$it" } + entries.removed.map { "-$it" } + entries.changed.map { "~$it" }
                ).joinToString(",", "[", "]")
            return "diff policies=${line(found.policies)} bindings=${line(found.bindings)} " +
                "roles=${line(found.roles)} sections=${found.sections.joinToString(",", "[", "]")}"
        }

        /** What happens around the control plane between steps: time passes, an instance vanishes, an outage. */
        /** `GET /changes` as [by] asks it (CTL-55): the ids it lists, in order, `none`, or `refused <status>`. */
        private suspend fun listing(argument: JsonNode): String = try {
            argument.only("by", "document", "state")
            val page = plane.changes(
                by(argument),
                argument.get("document")?.text()?.let(::documentKey),
                argument.get("state")?.text()?.let(ChangeState::valueOf),
                null,
                LISTING_LIMIT,
            ) { true }
            "changes " + (page.changes.map { it.id.value }.ifEmpty { listOf("none") }.joinToString(","))
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        private fun environment(action: String, argument: JsonNode): String = when (action) {
            "advance" -> "advanced".also { clock.now = clock.now.plus(Duration.parse(argument.text())) }
            "vanish" -> "vanished".also { vanished += argument.text() }
            "storeDown" -> {
                storeDown = argument.bool()
                if (storeDown) "store down" else "store up"
            }
            "oldOpenList" -> {
                memory.legacyOpenList = argument.bool()
                if (memory.legacyOpenList) "old list" else "no old list"
            }
            else -> {
                engine.outage = argument.bool()
                if (engine.outage) "down" else "up"
            }
        }

        /** What happens to the operators' credentials and the instance's audit between steps ([CREDENTIAL_STEPS]). */
        private fun credentials(action: String, argument: JsonNode): String = when (action) {
            "revoke" -> "revoked".also { revokedTokens += argument.text() }
            "revokeTokens" -> "revoked".also { revokedBefore[argument.text()] = clock.now.plusSeconds(1) }
            "provide" -> {
                argument.only("operator", "roles")
                provided[argument.required("operator").text()] = argument.get("roles")?.strings().orEmpty()
                "provided"
            }

            else -> "unaudited".also { changes = changes(ChangeListeners.NONE) }
        }

        private suspend fun step(action: String, argument: JsonNode): ChangeRequest = when (action) {
            "propose" -> propose(argument)
            "apply" -> apply(argument)
            "rollback" -> rollback(argument)
            "shadowed" -> changes.shadowed(ChangeId(argument.text()))
            "approve" -> target(argument, "candidate").let { (id, op, node) ->
                changes.approve(op, id, candidate(id, node))
            }

            "approveAsBefore" -> target(argument).let { (id, op) -> approvedAsBefore(id, op) }

            else -> transition(action, argument)
        }

        /**
         * `DELETE /break-glass/{binding}` through the control API (RFC 0007 §10.2): `{ "change": "c2", "by": "rita",
         * "binding": "glass" }`, the change it would make named `change`. Its result is the status, then the state of
         * the change it answers with, if any (`200 PUBLISHED`, `409`, `404`).
         */
        private suspend fun revoke(argument: JsonNode): String {
            argument.only("change", "by", "binding")
            val by = argument.required("by").text()
            val security = securities[by] ?: throw ScenarioFormatException("No operator $by")
            next = ChangeId(argument.required("change").text())
            documents += SnapshotKey.Platform
            val http =
                ControlHttp(
                    DefaultControlPlane(
                        changes,
                        revisions,
                        ChangeAuthority(authorizer, revocations = RevocationReader.NONE)
                    )
                )
            val path = "/break-glass/${argument.required("binding").text()}"
            val response = http.handle(ControlHttpRequest("DELETE", path, mapOf("reason" to "scenario")), security)
            val body = CoSecJson.mapper.readTree(response.body).takeIf { response.status / 100 == 2 }
            body?.let { named[it.required("id").text()] = changes.get(ChangeId(it.required("id").text())) }
            return listOfNotNull(response.status.toString(), body?.required("state")?.text()).joinToString(" ")
        }

        private suspend fun transition(action: String, argument: JsonNode): ChangeRequest {
            val (id, op) = if (action == "publish") target(argument, "ack", "lockout") else target(argument)
            return when (action) {
                "skipShadow" -> changes.skipShadow(op, id, "scenario")
                "stage" -> changes.startShadow(op, id)
                "publish" -> changes.publish(
                    op,
                    id,
                    ack(id, argument),
                    lockout = argument.get("lockout")?.bool() == true
                )
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

        private suspend fun query(action: String, argument: JsonNode): String = when (action) {
            "simulate" -> simulate(argument)
            "explain" -> explain(argument)
            else -> viaFacade(argument)
        }

        /**
         * A call through the control plane's facade (`CTL-65`), as an application's own code makes it for the request
         * of `by`: `propose`, with the body `POST /changes` takes, or `document`, a key to read. Its result is the
         * proposed change's state, `read`, or `refused <status>`.
         */
        private suspend fun viaFacade(argument: JsonNode): String = try {
            argument.only("by", "change", "propose", "document")
            val caller = ControlCaller.of(checkNotNull(by(argument).security).securityContext)
            val request = argument.get("propose")
            if (request != null) {
                val id = argument.required("change").text()
                next = ChangeId(id)
                val answer = CoSecJson.mapper.readTree(
                    facade.propose(caller, CoSecJson.mapper.writeValueAsString(request))
                )
                named[id] = changes.get(ChangeId(id))
                answer.required("state").text()
            } else {
                facade.document(caller, documentKey(argument.required("document").text()))
                "read"
            }
        } catch (error: ControlRefusedException) {
            "refused ${error.status}" +
                (CoSecJson.mapper.readTree(error.problem).get("conflict")?.let { " ${it.asString()}" } ?: "")
        }

        /**
         * The kinds of a change's events so far (`CTL-63`), in the order the listener received them: the order they were
         * written (`CTL-66`).
         */
        private suspend fun audited(argument: JsonNode): String {
            auditing.awaitDelivered()
            val id = argument.text()
            return "audited " + audit.events.filter { it.change.value == id }.joinToString(",") { it.kind.name }
        }

        /**
         * `GET /documents?kind=` (`CTL-68`): `documents <key>,...` or `documents none`, the keys of the page the caller
         * is shown, or `refused <status>`.
         */
        private suspend fun documents(argument: JsonNode): String = try {
            argument.only("by", "kind")
            val kind = argument.required("kind").text().let { DocumentKind.valueOf(it.uppercase()) }
            val page = plane.listing.list(by(argument), kind, null, LISTING_LIMIT)
            "documents " + page.documents.map { it.toString() }.ifEmpty { listOf("none") }.joinToString(",")
        } catch (error: ChangeRejectedException) {
            refused(error)
        } catch (_: IllegalArgumentException) {
            "refused 400"
        }

        /**
         * The author's origin in `GET /changes/{id}` as `by` reads it (`CTL-70`): whether it was asserted, by which method,
         * and the members `by` is given; `origin none` for a change no request made, or `refused <status>`.
         */
        private suspend fun origin(argument: JsonNode): String = try {
            argument.only("by", "change")
            val reader = by(argument)
            val change = plane.get(reader, ChangeId(argument.required("change").text()))
            val origin = ChangeAnswer.tree(change, wholeOrigin = plane.origins.whole(reader, change)).get("origin")
            if (origin == null) {
                "origin none"
            } else {
                val members = origin.properties().flatMap { (name, value) ->
                    if (value.isObject) value.properties().map { "$name.${it.key}" } else listOf(name)
                }.sorted()
                "origin asserted ${origin.required("asserted").bool()} via ${origin.get("via")?.get("method")?.text()}; " +
                    "members ${members.joinToString(",")}"
            }
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        /** `GET /documents/{key}` as `by`: `read`, or `refused <status>`. */
        private suspend fun readDocument(argument: JsonNode): String = try {
            argument.only("by", "document")
            plane.read(by(argument), documentKey(argument.required("document").text()))
            "read"
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        /** The steps of the control API's answers (`CTL-61`, `CTL-62`). */
        private suspend fun answers(action: String, argument: JsonNode): String = when (action) {
            "answer" -> answer(argument)
            "status" -> status(argument)
            "acknowledgement" -> acknowledgement(argument)
            else -> propagation(argument)
        }

        /** The members of a change's answer (`CTL-61`), as `GET /changes/{id}` writes them. */
        private suspend fun answer(argument: JsonNode): String {
            val answer = ChangeAnswer.tree(changes.get(ChangeId(argument.text())))
            fun names(node: JsonNode) = node.properties().map { it.key }.sorted().joinToString(",")
            return "members ${names(answer)}" + listOf("shadow", "approval")
                .mapNotNull { name -> answer.get(name)?.let { "; $name ${names(it)}" } }.joinToString("")
        }

        /**
         * An instance's status and its resident record, as its heartbeat writes them: the revision of each document it
         * holds; with `"resident": false` its resident record is not written (`CTL-75`: `propagation` counts it unknown).
         */
        private suspend fun status(argument: JsonNode): String {
            argument.only("instance", "documents", "acknowledges", "shadows", "unreadable", "resident")
            val instance = argument.required("instance").text()
            if (argument.get("unreadable")?.bool() == true) {
                unreadable += instance
                return "status"
            }
            unreadable -= instance
            val documents = argument.required("documents").properties().associate { (key, revision) ->
                documentKey(key) to revision.asLong()
            }
            if (argument.get("resident")?.bool() != false) {
                reports.write(ResidentDocuments(instance, clock.now, documents))
            }
            val windows = argument.get("acknowledges")?.strings()?.mapTo(HashSet()) { change ->
                changes.get(ChangeId(change)).shadow?.window
                    ?: throw ScenarioFormatException("An acknowledged change is staged: $change")
            }
            val shadows = argument.get("shadows")?.bool() ?: true
            reports.write(InstanceStatus(instance, clock.now, windows = windows, shadows = shadows))
            return "status"
        }

        /** Which live instances acknowledge a change's open window (`CTL-10`), as `GET /changes/{id}` answers it. */
        private suspend fun acknowledgement(argument: JsonNode): String {
            val read = changes.acknowledgements.of(changes.get(ChangeId(argument.text()))) ?: return "no open window"
            return "acknowledged ${read.acknowledged} waiting " + read.waitingFor.joinToString(",").ifEmpty { "none" }
        }

        /**
         * A revision's propagation (`CTL-62`, `CTL-75`): `installed <n> holding <n> recent <n> unknown <n>`, or
         * `refused <status>`.
         */
        private suspend fun propagation(argument: JsonNode): String = try {
            argument.only("by", "document", "revision")
            val propagation = plane.propagation(
                by(argument),
                documentKey(argument.required("document").text()),
                argument.required("revision").asLong(),
            )
            "installed ${propagation.installed} holding ${propagation.holding} recent ${propagation.recent}" +
                " unknown ${propagation.unknown}"
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        /**
         * The control plane's `simulate` (RFC 0007 §10.3): `{ "by": "alice", "request": { "tenant": "acme", "principal":
         * "bob", "roles": [], "app": "", "space": "", "action": "order:read", "resource": "order/1" }, "candidate": "c1" }`.
         * Its result is the decision, then `by <policy>` when the caller may see it, then `; candidate <decision>`.
         */
        private suspend fun simulate(argument: JsonNode): String = try {
            argument.only("by", "request", "candidate")
            val result = plane.simulate(
                by(argument),
                simulatedRequest(argument.required("request")),
                argument.get("candidate")?.text()?.let(::ChangeId)
            )
            shown(result.enforced) + (result.candidate?.let { "; candidate ${shown(it)}" } ?: "")
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        /**
         * The control plane's `explain` (RFC 0007 §10.3), at `at` (the revisions an audit event recorded) or at the
         * heads: the decision as [simulate] writes it, then `at <platform>/<app>/<tenant>`, the revisions it decided
         * on (`-` for an app document it did not read).
         */
        private suspend fun explain(argument: JsonNode): String = try {
            argument.only("by", "request", "at")
            val at = argument.get("at")?.let {
                it.only("platform", "app", "tenant")
                ViewVersion(
                    it.required("platform").asLong(),
                    it.get("app")?.asLong(),
                    it.required("tenant").asLong()
                )
            }
            val result = plane.explain(by(argument), simulatedRequest(argument.required("request")), at)
            val version = result.version
            shown(result.decision) + " at ${version.platform}/${version.app ?: "-"}/${version.tenant}"
        } catch (error: ChangeRejectedException) {
            refused(error)
        }

        /** The decision, `by <policy>` when the caller may see it, and `(cause <kind>)` or `(reason <why>)` (CTL-61). */
        private fun shown(
            decision: DecisionView
        ) = decision.decision + (decision.by?.let { " by ${it.policy}" } ?: "") +
            (decision.cause?.let { " (cause $it)" } ?: "") + (decision.reason?.let { " (reason $it)" } ?: "")

        private fun simulatedRequest(request: JsonNode): SimulatedRequest {
            request.only("tenant", "principal", "roles", "app", "space", "action", "resource", "headers", "requestAttributes")
            val action = request.required("action").text().split(':', limit = 2)
            val resource = request.required("resource").text().split('/', limit = 2)
            return SimulatedRequest(
                tenant = TenantId(request.required("tenant").text()),
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
            return changes.propose(by(argument), key, head, operations, "scenario", kind)
        }

        /** `:apply` (RFC 0007 §6.7): the change that makes the document's head into `desired`, by `mode`. */
        private suspend fun apply(argument: JsonNode): ChangeRequest {
            argument.only("change", "by", "document", "desired", "mode", "revision")
            val key = documentKey(argument.required("document").text())
            documents += key
            next = ChangeId(argument.required("change").text())
            // The header names the revision the file was read at: the head's, unless the scenario says another (UC-5).
            val head = revisions.head(key)?.revision ?: 0
            val read = argument.get("revision")?.asLong() ?: head.coerceAtLeast(1)
            val desired = decode(key, argument.required("desired"), head).copy(revision = read)
            val mode = ApplyMode.valueOf(argument.required("mode").text())
            return plane.apply(by(argument), key, desired, mode, "scenario")
        }

        /** `{ "change": "c2", "by": "walt", "document": "platform", "revision": 1 }`: a rollback (RFC 0007 §5.6). */
        private suspend fun rollback(argument: JsonNode): ChangeRequest {
            argument.only("change", "by", "document", "revision")
            val key = documentKey(argument.required("document").text())
            documents += key
            next = ChangeId(argument.required("change").text())
            return changes.rollback(by(argument), key, argument.required("revision").asLong(), "scenario")
        }

        private suspend fun candidate(id: ChangeId, node: JsonNode): String = when (val which = node.get("candidate")?.text() ?: "own") {
            "own" -> store.change(id)?.value?.candidate.orEmpty()
            "other" -> "0".repeat(DIGEST_LENGTH)
            else -> throw ScenarioFormatException("candidate is own or other, not $which")
        }

        /**
         * Who takes a step: its `by` with operators, otherwise a platform principal, the trust root of every document
         * (RFC 0007 §7.3): a tenant principal holding `cosec:approve` is not one (RC1-10).
         */
        private fun by(step: JsonNode): Operator {
            val name = step.get("by")?.text()
            if (authorized != (name != null)) {
                throw ScenarioFormatException(
                    "A step names who takes it exactly when the scenario has operators: $step"
                )
            }
            if (name == null) {
                return PLATFORM
            }
            return operators[name] ?: throw ScenarioFormatException("No operator $name")
        }

        /**
         * [id] approved by [op] as an instance before RC1-11 stored it: `READY`, the approval naming no credential, written
         * to the store as that instance would, past every check of this one's `approve`.
         */
        private suspend fun approvedAsBefore(id: ChangeId, op: Operator): ChangeRequest {
            val current = store.change(id) ?: throw ScenarioFormatException("No change ${id.value} to approve")
            val change = current.value
            val approval = Approval(
                op.actor,
                clock.now,
                change.candidate ?: throw ScenarioFormatException("Change ${id.value} has no candidate"),
                change.escalations.map { it.subject },
                op.memberships,
                op.attributes,
            )
            val approved = change.copy(state = ChangeState.READY, approval = approval)
            check(store.putChange(current.digest, approved, change.state)) { "Change ${id.value} moved meanwhile." }
            return approved
        }

        /** A transition's change and operator: `"c1"`, or `{ "change": "c1", "by": "bob", ... }`. */
        private suspend fun target(argument: JsonNode, vararg fields: String): Triple<ChangeId, Operator, JsonNode> {
            if (!argument.isString) {
                argument.only("change", "by", *fields)
            }
            val id = ChangeId(if (argument.isString) argument.text() else argument.required("change").text())
            // A change that does not exist is still named, so that the step answers for it as for any other.
            return Triple(id, by(argument), argument)
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
        val (kind, owner) = ownerOf(key)
        val document = JsonNodeFactory.instance.objectNode()
            .put("schema", PolicyDocumentCodec.SCHEMA).put("revision", head + 1).put("publishedAt", NOW.toString())
        document.setAll(body as? ObjectNode ?: throw ScenarioFormatException("The $key document is not an object"))
        return PolicyDocumentCodec.decode(kind, owner, CoSecJson.mapper.writeValueAsBytes(document))
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
        val (kind, owner) = ownerOf(key)
        val (name, value) = node.fields().entries.singleOrNull() ?: throw ScenarioFormatException("An operation is one entry: $node")
        return when (name) {
            "putPolicy" -> Operation.PutPolicy(PolicyDocumentCodec.decodePolicy(value))
            "putBinding" -> Operation.PutBinding(PolicyDocumentCodec.decodeBinding(value, kind, owner))
            "removePolicy" -> Operation.RemovePolicy(PolicyId(value.text()))
            "removeBinding" -> Operation.RemoveBinding(BindingId(value.text()))
            "putRateLimit" -> Operation.PutRateLimit(PolicyDocumentCodec.decodeRateLimit(value))
            "removeRateLimit" -> Operation.RemoveRateLimit(value.text())
            "setBoundary" -> Operation.SetBoundary(if (value.isNull) null else PolicyId(value.text()))
            else -> otherOperation(key, kind, name, value)
        }
    }

    private fun otherOperation(key: SnapshotKey, kind: DocumentKind, name: String, value: JsonNode): Operation = when (name) {
        // As the control API's request bodies write it: what it refuses is the step's `malformed` (CTL-28).
        "wire" -> ChangeRequestCodec.operation(key, value)
        "putRole" -> Operation.PutRole(PolicyDocumentCodec.decodeRole(value, kind))
        "removeRole" -> Operation.RemoveRole(RoleId(value.text()))
        "putCatalog" -> Operation.PutCatalog(if (value.isNull) null else PolicyDocumentCodec.decodeCatalog(value))
        else -> throw ScenarioFormatException("Unknown operation $name")
    }

    fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw ScenarioFormatException("A document is platform, app:<id> or tenant:<id>, not $text")
    }

    const val DIGEST_LENGTH = 64

    /** A REQUIRED change listener that records everything. */
    private val CREDENTIAL_STEPS = setOf("revoke", "revokeTokens", "provide", "unaudited")

    /** The control plane's queries, and a call through its facade (`CTL-65`). */
    private val QUERIES = setOf("simulate", "explain", "viaFacade")

    /** The steps that read what the control plane holds or answers, and the instance statuses it reads. */
    private val READS = setOf(
        "diff", "staged", "listed", "samples", "rateLimits", "answer", "status", "propagation", "audited", "documents", "read", "acknowledgement",
        "origin",
    )

    /** The page size of a `changes` step: enough for a scenario's changes. */
    private const val LISTING_LIMIT = 100

    private class RecordingAudit : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED
        val events = java.util.concurrent.CopyOnWriteArrayList<ChangeEvent>()

        override suspend fun onChange(event: ChangeEvent) {
            events += event
        }
    }
}

/** A refusal as a scenario writes it: its status, and a `409`'s conflict (`CTL-80`), `refused 409 SLOT_HELD`. */
private fun refused(error: ChangeRejectedException): String =
    "refused ${error.status}" + (error.conflict?.let { " ${it.name}" } ?: "")
