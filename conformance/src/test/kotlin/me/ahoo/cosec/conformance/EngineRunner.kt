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
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.membership.InMemoryMembershipProvider
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.membership.MembershipUnavailableException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.runtime.authn.MembershipSource
import tools.jackson.databind.JsonNode
import java.time.Instant
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

/**
 * The `engine` runner (RFC 0008 §3.3): documents → compile → [SnapshotView] → [FlatEngine].
 *
 * Before the engine it runs the steps the pipeline owns: memberships
 * through [MembershipResolver], whose failure is the `MembershipUnavailable` cause of RFC 0004 §4.5.2, and the scope
 * through [ScopeResolver]. It hands the engine the documents RFC 0004 §7.4 says a request needs: the platform, the
 * principal's own tenant and, for a scope with an app, that app; a document the scenario does not write exists and is
 * empty. Every scenario is evaluated at each trace level, and they must agree (ALG-8).
 *
 * Scenario fields besides the header (all optional; the defaults are an authenticated `alice` of tenant `acme` without
 * memberships, reading `order/1` with `order:read` on 2026-11-01). An anonymous credential's tenant and claims are
 * passed on as given, so a scenario can check that the resolver ignores them:
 *
 * ```jsonc
 * "documents":  { "platform": {...}, "apps": { "<app>": {...} }, "tenants": { "<tenant>": {...} },
 *                 "notLoaded": ["tenant" | "app"], "unavailable": ["platform" | "app" | "tenant"] },
 * "credential": { "anonymous": false, "tenant": "acme", "id": "alice", "roles": [...], "policies": [...],
 *                 "membership": "provider", "attributes": { ... } },
 * "membershipProvider": { "roles": { "<space>": [...] }, "attachments": [...] } | { "fails": "message" },
 * "request":    { "app": "", "space": "", "hostApp": null, "action": "order:read",
 *                 "resource": { "type": "order", "id": "1", "attributes": {} }, "time": "...", "ip": "...",
 *                 "device": "...", "blocked": false, "headers": {}, "attributes": {} }
 * ```
 */
class EngineRunner(
    private val compiler: SnapshotCompiler = DocumentSnapshotCompiler,
    private val engine: FlatEngine = CoSecFlatEngine,
) : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome {
        val node = scenario.node
        node.only("\$comment", "rules", "polarity", "level", "description", "documents", "credential", "membershipProvider", "request", "expect")
        val documents = node.get("documents") ?: EMPTY
        documents.only("platform", "apps", "tenants", "notLoaded", "unavailable")
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
        val credential = Credential(node.get("credential") ?: EMPTY)
        val memberships = try {
            memberships(credential, node.get("membershipProvider"))
        } catch (error: MembershipUnavailableException) {
            return Outcome.Decided(Decision.Indeterminate(Cause.MembershipUnavailable(error)))
        }
        val request = node.get("request") ?: EMPTY
        request.only("app", "space", "hostApp", "action", "resource", "time", "ip", "device", "blocked", "headers", "attributes")
        val resolution = ScopeResolver.resolve(
            ScopeSubject(credential.tenant, credential.authenticated, memberships),
            DeclaredScope(request.get("app")?.text().orEmpty(), request.get("space")?.text().orEmpty()),
            HostScope(request.get("hostApp")?.textOrNull()?.let(::AppId)),
        )
        val scope = when (resolution) {
            is ScopeResolution.Rejected -> return Outcome.Rejected(resolution.reason)
            is ScopeResolution.Resolved -> resolution.scope
        }
        val flat = flatRequest(credential, memberships, scope, request)
        val view = view(snapshot, scope, documents)
        val decisions = TraceLevel.entries.map { engine.evaluate(flat, view, it).decision }
        // Errors are new exceptions at each level, so decisions compare as rendered.
        check(
            decisions.map { Expectations.render(it) }.distinct().size == 1
        ) { "The decision depends on the trace level: $decisions" }
        return Outcome.Decided(decisions.first())
    }

    private fun memberships(credential: Credential, provider: JsonNode?): Memberships {
        if (!credential.authenticated) {
            // Whatever an anonymous credential claims reaches ScopeResolver, which must ignore it (SCP-6).
            return MembershipClaims.parse(credential.roles, credential.policies)
        }
        val resolver = MembershipResolver(provider?.let { provider(credential, it) })
        val principal = credential.principal()
        return runBlocking {
            resolver.resolve(
                credential.tenant,
                PrincipalId(principal.id),
                MembershipSource.of(principal)
            )
        }
    }

    private fun provider(credential: Credential, node: JsonNode): MembershipProvider {
        node.only("roles", "attachments", "fails")
        node.get("fails")?.let { fails ->
            return MembershipProvider { _, _ -> throw IllegalStateException(fails.text()) }
        }
        val roles = node.get("roles")?.fields().orEmpty().map { (space, ids) ->
            SpaceId(space) to ids.strings().mapTo(LinkedHashSet(), ::RoleId)
        }.toMap()
        val attachments = node.get("attachments")?.strings().orEmpty().map(::PolicyId)
        return InMemoryMembershipProvider().apply {
            put(
                credential.tenant,
                PrincipalId(credential.id),
                Memberships(Memberships.Source.PROVIDER, roles, attachments)
            )
        }
    }

    private fun flatRequest(credential: Credential, memberships: Memberships, scope: Scope, request: JsonNode): FlatRequest {
        val action = (request.get("action")?.text() ?: "order:read").let {
            Action(it.substringBefore(':'), it.substringAfter(':'))
        }
        val resource = resource(request.get("resource"))
        val time = Instant.parse(request.get("time")?.text() ?: DEFAULT_TIME).toEpochMilli()
        val ip = request.get("ip")?.text()
        val device = request.get("device")?.text()
        val blocked = request.get("blocked")?.bool() ?: false
        val facts = RequestFacts(
            authenticated = credential.authenticated,
            principal = credential.id,
            scope = scope,
            memberships = memberships,
            subjectAttributes = credential.attributes,
            action = action,
            resource = resource,
            ip = ip?.let(IpAddress::parse),
            time = time,
            device = device,
            blocked = blocked,
            headers = request.get(
                "headers"
            )?.fields().orEmpty().map { (name, value) -> name.lowercase() to value.text() }.toMap(),
            requestAttributes = request.get("attributes")?.fields().orEmpty().mapValues { it.value.text() },
        )
        val authz = AuthzRequest(
            subject = Subject(
                id = credential.id,
                tenantId = scope.tenant.value,
                authenticated = credential.authenticated,
                roles = emptySet(),
                policies = emptySet(),
                attributes = credential.attributes,
                root = false,
            ),
            scope = DeclaredRequestScope.declared(scope.tenant.value, scope.app.value, scope.space.value),
            action = action,
            resource = resource,
            environment = Environment(ip.orEmpty(), "", device.orEmpty(), blocked),
            host = facts,
        )
        return FlatRequest(authz, scope, memberships, time, facts.host)
    }

    private fun resource(node: JsonNode?): Resource {
        if (node == null) return Resource("order", "1", emptyMap())
        node.only("type", "id", "attributes")
        return Resource(
            node.required("type").text(),
            node.get("id")?.text().orEmpty(),
            node.get("attributes")?.fields().orEmpty().mapValues { it.value.text() },
        )
    }

    private fun view(snapshot: CompiledSnapshot, scope: Scope, documents: JsonNode): SnapshotView {
        val notLoaded = documents.get("notLoaded")?.strings().orEmpty().map(::kind).toSet()
        val tenant = if (DocumentKind.TENANT in notLoaded) {
            null
        } else {
            snapshot.tenants[scope.tenant.value] ?: CompiledDocument.tenant(scope.tenant, emptyList(), emptyList())
        }
        val app = when {
            scope.app.value.isEmpty() || DocumentKind.APP in notLoaded -> null
            else -> snapshot.apps[scope.app.value] ?: CompiledDocument.app(scope.app, emptyList(), emptyList())
        }
        val unresolved = documents.get("unavailable")?.strings().orEmpty().map {
            Cause.SourceUnavailable(kind(it), IllegalStateException("The scenario makes the $it document unavailable"))
        }
        return SnapshotView(snapshot.platform, app, tenant, unresolved)
    }

    private class Credential(node: JsonNode) {
        init {
            node.only("anonymous", "tenant", "id", "roles", "policies", "membership", "attributes")
        }

        val authenticated: Boolean = !(node.get("anonymous")?.bool() ?: false)
        val tenant: TenantId = TenantId(
            node.get("tenant")?.text() ?: if (authenticated) "acme" else TenantId.DEFAULT.value
        )
        val id: String = node.get("id")?.text() ?: if (authenticated) "alice" else CoSecPrincipal.ANONYMOUS_ID
        val roles: Set<String> = node.get("roles")?.strings().orEmpty().toCollection(LinkedHashSet())
        val policies: Set<String> = node.get("policies")?.strings().orEmpty().toCollection(LinkedHashSet())
        val marker: String? = node.get("membership")?.text()
        val attributes: Map<String, String> = node.get("attributes")?.fields().orEmpty().mapValues { it.value.text() }

        fun principal(): CoSecPrincipal {
            val principal = SimplePrincipal(id, policies, roles, attributes)
            return marker?.let { MembershipMarkedPrincipal(principal, it) } ?: principal
        }
    }

    private companion object {
        const val DEFAULT_TIME = "2026-11-01T00:00:00Z"
        val EMPTY: JsonNode = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()

        fun kind(name: String): DocumentKind = when (name) {
            "platform" -> DocumentKind.PLATFORM
            "app" -> DocumentKind.APP
            "tenant" -> DocumentKind.TENANT
            else -> throw ScenarioFormatException("A document is platform, app or tenant, not $name")
        }
    }
}
