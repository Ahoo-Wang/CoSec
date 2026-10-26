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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.membership.InMemoryMembershipProvider
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosid.StringIdGenerator
import tools.jackson.databind.JsonNode
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import me.ahoo.cosec.runtime.Outcome as PipelineOutcome

/**
 * The `pipeline` runner (RFC 0008 §3.3): an HTTP request → [Pipeline] → its outcome, with the scenario's documents
 * compiled as the `engine` runner compiles them and test doubles for every SPI the pipeline calls. The adapters
 * (RFC 0006 PR-7) and listeners (PR-6) are not part of it yet.
 *
 * The pipeline gives the engine an `http` action and resource (`http:<METHOD>` on the path) and no host facts, so the
 * statements of a pipeline scenario match on action and resource only, never on conditions.
 *
 * ```jsonc
 * "documents":          { "platform": {...}, "apps": {...}, "tenants": {...}, "unavailable": ["tenant", ...] },
 * "http":               { "method": "GET", "path": "/orders", "query": "a=b", "headers": { "<name>": "v" | ["v", ...] },
 *                         "hostApp": null },
 * "authentication":     { "result": "anonymous" | "authenticated" | "rejected" | "unavailable" | "throws" | "hangs",
 *                         "reason": "TOKEN_EXPIRED", "tenant": "acme", "id": "alice", "roles": [...], "policies": [...],
 *                         "membership": "provider" },
 * "membershipProvider": { "roles": { "<space>": [...] }, "attachments": [...] } | { "fails": "message" } | { "hangs": true },
 * "snapshot":           { "fails": "message" } | { "hangs": true },      // otherwise every document is installed
 * "options":            { "timeoutMillis": 5000, "authenticationTimeoutMillis": 1000, "membershipTimeoutMillis": 500 },
 * "cancel":             "authentication" | "memberships" | "snapshot"    // the caller goes away while it waits there
 * ```
 *
 * The outcome is `{ "status": 200, "proceed": true }`, `{ "status": 503, "type": "unavailable" }` or, when the caller
 * cancelled, `{ "outcome": "none" }`.
 */
class PipelineRunner(private val compiler: SnapshotCompiler = AdaptedSnapshotCompiler) : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome {
        val node = scenario.node
        node.only(
            "\$comment", "rules", "polarity", "level", "description", "documents", "http", "authentication",
            "membershipProvider", "snapshot", "options", "cancel", "expect",
        )
        val documents = node.get("documents") ?: EMPTY
        documents.only("platform", "apps", "tenants", "unavailable")
        val snapshot = try {
            compiler.compile(
                ScenarioDocuments(
                    platform = documents.get("platform"),
                    apps = documents.get("apps")?.fields().orEmpty(),
                    tenants = documents.get("tenants")?.fields().orEmpty(),
                )
            )
        } catch (error: DocumentException) {
            return Outcome.NotCompiled(error.message.orEmpty())
        }
        val cancelAt = node.get("cancel")?.text()?.also {
            if (it !in CANCEL_POINTS) throw ScenarioFormatException("cancel is one of $CANCEL_POINTS, not $it")
        }
        val reached = CompletableDeferred<Unit>()
        fun reach(point: String) {
            if (point == cancelAt) reached.complete(Unit)
        }

        val authentication = node.get("authentication") ?: EMPTY
        val provider = node.get("membershipProvider")?.let { provider(authentication, it) { reach("memberships") } }
        val pipeline = Pipeline(
            authenticator = authenticator(authentication) { reach("authentication") },
            snapshots = SuspendingSnapshotSource(
                views(snapshot, documents, node.get("snapshot")) { reach("snapshot") }
            ),
            membershipProvider = provider,
            idGenerator = StringIdGenerator { REQUEST_ID },
            options = options(node.get("options") ?: EMPTY),
        )
        val facts = facts(node.get("http") ?: EMPTY)
        if (cancelAt != null) {
            return cancelled(pipeline, facts, reached)
        }
        return answered(runBlocking { pipeline.evaluate(facts) })
    }

    /** Evaluates, and cancels the caller once the pipeline waits at the scenario's point. */
    private fun cancelled(
        pipeline: Pipeline,
        facts: HttpFacts,
        reached: CompletableDeferred<Unit>
    ): Outcome = runBlocking {
        var outcome: PipelineOutcome? = null
        var cancelled = false
        val caller = launch {
            try {
                outcome = pipeline.evaluate(facts)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        // A pipeline that answers without reaching the point leaves nothing to cancel; that outcome is reported.
        launch { caller.join().also { reached.complete(Unit) } }
        reached.await()
        caller.cancelAndJoin()
        val answer = outcome
        when {
            answer != null -> answered(answer)
            cancelled -> Outcome.Cancelled
            else -> error("The pipeline neither answered nor was cancelled.")
        }
    }

    private fun answered(outcome: PipelineOutcome): Outcome =
        Outcome.Answered(outcome.status, outcome.proceed, outcome.problem?.type?.slug)

    private fun authenticator(node: JsonNode, reached: () -> Unit): Authenticator {
        node.only("result", "reason", "tenant", "id", "roles", "policies", "membership")
        return when (val result = node.get("result")?.text() ?: "authenticated") {
            "anonymous" -> Authenticator { AuthnResult.Anonymous }
            "authenticated" -> Authenticator { authenticated(node) }
            "rejected" -> Authenticator {
                AuthnResult.Rejected(
                    AuthnResult.RejectReason.valueOf(node.required("reason").text())
                )
            }
            "unavailable" -> Authenticator {
                AuthnResult.Unavailable(
                    IllegalStateException("The scenario's key store is down")
                )
            }
            "throws" -> Authenticator { throw IllegalStateException("The scenario's authenticator throws") }
            "hangs" -> Authenticator {
                reached()
                awaitCancellation()
            }

            else -> throw ScenarioFormatException("Unknown authentication result $result")
        }
    }

    private fun authenticated(node: JsonNode): AuthnResult {
        val memberships = if (node.get("membership")?.text() == MembershipClaims.PROVIDER) {
            null
        } else {
            MembershipClaims.parse(
                node.get("roles")?.strings().orEmpty().toSet(),
                node.get("policies")?.strings().orEmpty().toSet(),
            )
        }
        val principal = AuthenticatedPrincipal(PrincipalId(node.id()), TenantId(node.tenant()), memberships)
        return AuthnResult.Authenticated(principal, "scenario")
    }

    private fun provider(authentication: JsonNode, node: JsonNode, reached: () -> Unit): MembershipProvider {
        node.only("roles", "attachments", "fails", "hangs")
        node.get("fails")?.let { fails ->
            return MembershipProvider { _, _ -> CompletableFuture.failedFuture(IllegalStateException(fails.text())) }
        }
        if (node.get("hangs")?.bool() == true) {
            return MembershipProvider { _, _ ->
                reached()
                CompletableFuture()
            }
        }
        val roles = node.get("roles")?.fields().orEmpty().map { (space, ids) ->
            SpaceId(space) to ids.strings().mapTo(LinkedHashSet(), ::RoleId)
        }.toMap()
        val attachments = node.get("attachments")?.strings().orEmpty().map(::PolicyId)
        return InMemoryMembershipProvider().apply {
            put(
                TenantId(authentication.tenant()),
                PrincipalId(authentication.id()),
                Memberships(Memberships.Source.PROVIDER, roles, attachments),
            )
        }
    }

    /** Every document installed, unless the scenario's snapshot source fails or hangs on load. */
    private fun views(
        snapshot: CompiledSnapshot,
        documents: JsonNode,
        node: JsonNode?,
        reached: () -> Unit
    ): SnapshotViews {
        node?.only("fails", "hangs")
        val unresolved = documents.get("unavailable")?.strings().orEmpty().map {
            Cause.SourceUnavailable(kind(it), IllegalStateException("The scenario makes the $it document unavailable"))
        }

        fun view(key: ViewKey) = SnapshotView(
            snapshot.platform,
            key.app?.let { snapshot.apps[it.value] ?: CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { snapshot.tenants[it.value] ?: CompiledDocument.tenant(it, emptyList(), emptyList()) },
            unresolved,
        )
        val fails = node?.get("fails")?.text()
        val hangs = node?.get("hangs")?.bool() == true
        return object : SnapshotViews {
            override fun peek(key: ViewKey): SnapshotView? = if (fails != null || hangs) null else view(key)

            override fun load(key: ViewKey): CompletableFuture<SnapshotView> {
                reached()
                return when {
                    fails != null -> CompletableFuture.failedFuture(IllegalStateException(fails))
                    hangs -> CompletableFuture()
                    else -> CompletableFuture.completedFuture(view(key))
                }
            }
        }
    }

    private fun options(node: JsonNode): PipelineOptions {
        node.only("timeoutMillis", "authenticationTimeoutMillis", "membershipTimeoutMillis")
        val defaults = PipelineOptions()
        fun millis(field: String) = node.get(field)?.let { it.asLong().milliseconds }
        return PipelineOptions(
            timeout = millis("timeoutMillis") ?: defaults.timeout,
            authenticationTimeout = millis("authenticationTimeoutMillis") ?: defaults.authenticationTimeout,
            membershipTimeout = millis("membershipTimeoutMillis") ?: defaults.membershipTimeout,
        )
    }

    private fun facts(node: JsonNode): HttpFacts {
        node.only("method", "path", "query", "headers", "hostApp")
        val headers = node.get("headers")?.fields().orEmpty().mapValues { (_, value) ->
            if (value.isArray) value.strings() else listOf(value.text())
        }
        return HttpFacts(
            method = node.get("method")?.text() ?: "GET",
            rawPath = node.get("path")?.text() ?: "/orders",
            rawQuery = node.get("query")?.textOrNull(),
            headers = Headers.of(headers),
            cookies = Cookies.EMPTY,
            remoteAddress = "10.0.0.1",
            host = HostScope(node.get("hostApp")?.textOrNull()?.let(::AppId)),
        )
    }

    private fun JsonNode.tenant(): String = get("tenant")?.text() ?: "acme"

    private fun JsonNode.id(): String = get("id")?.text() ?: "alice"

    private companion object {
        const val REQUEST_ID = "scenario-request"
        val CANCEL_POINTS = setOf("authentication", "memberships", "snapshot")
        val EMPTY: JsonNode = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()

        fun kind(name: String): DocumentKind = when (name) {
            "platform" -> DocumentKind.PLATFORM
            "app" -> DocumentKind.APP
            "tenant" -> DocumentKind.TENANT
            else -> throw ScenarioFormatException("A document is platform, app or tenant, not $name")
        }
    }
}
