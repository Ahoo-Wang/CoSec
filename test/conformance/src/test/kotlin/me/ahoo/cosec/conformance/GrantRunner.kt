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

import me.ahoo.cosec.control.grant.GrantAnalyzer
import me.ahoo.cosec.control.grant.GrantChange
import me.ahoo.cosec.control.grant.GrantDocument
import me.ahoo.cosec.control.grant.GrantPolicy
import me.ahoo.cosec.control.grant.GrantSnapshot
import me.ahoo.cosec.control.grant.GrantStatement
import me.ahoo.cosec.control.grant.Grantor
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.instant
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * The `control` runner for the grant analysis of RFC 0007 §7.4: a grantor, the published documents its own rights
 * come from, and a candidate tenant document. The outcome is the analysis's verdict for each fragment the candidate
 * grants. Wiring the analysis into the publish flow (accept, reject, approval) comes with RFC 0007 PR-6; this runner
 * then gains the `ControlPlane` writes of RFC 0008 §3.3.
 *
 * ```jsonc
 * "documents": { "platform": {...}, "apps": {...}, "tenants": { "acme": {...} } },   // published, the engine syntax
 * "grantor": { "tenant": "acme", "id": "gina", "roles": ["ops@team-a"], "policies": [], "attributes": {} },
 * "candidate": { "tenant": "acme", "document": {...} },                             // replaces that tenant's document
 * "trustRoot": false,
 * "expect": { "fragments": [ { "policy": "p", "statement": 0, "action": "order:read", "resource": "order/{id}",
 *                              "verdict": "YES" | "UNKNOWN", "reason": "NOT_COVERED", "shortfall": "SCOPE" } ],
 *             "unprotect": ["policy:p", "binding:b"] }
 * ```
 *
 * A policy may also say `"protected": true`, and a binding `"protected": true`. Documents are read here from the
 * scenario syntax; the document codec of RFC 0004 PR-4 replaces this reader.
 */
object GrantRunner : ScenarioRunner {
    private val analyzer = GrantAnalyzer()

    /** The scenario time: bindings expire against it. */
    private val NOW = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

    override fun run(scenario: Scenario): Outcome {
        val node = scenario.node
        node.only("\$comment", "rules", "polarity", "level", "description", "documents", "grantor", "candidate", "trustRoot", "expect")
        val documents = node.get("documents")
        documents?.only("platform", "apps", "tenants")
        val snapshot = GrantSnapshot(
            platform = documents?.get("platform")?.let { document(it, null) } ?: GrantDocument.EMPTY,
            apps = documents?.get(
                "apps"
            )?.fields().orEmpty().entries.associate { (app, it) -> AppId(app) to document(it, null) },
            tenants = documents?.get("tenants")?.fields().orEmpty().entries
                .associate { (tenant, it) -> TenantId(tenant) to document(it, TenantId(tenant)) },
            now = NOW,
        )
        val candidate = node.required("candidate")
        candidate.only("tenant", "document")
        val tenant = TenantId(candidate.required("tenant").text())
        val change = GrantChange(tenant, document(candidate.required("document"), tenant))
        val analysis = analyzer.analyze(
            grantor(node.required("grantor")),
            snapshot,
            change,
            node.get("trustRoot")?.bool() ?: false
        )
        return Outcome.Granted(analysis)
    }

    private fun grantor(node: JsonNode): Grantor {
        node.only("tenant", "id", "roles", "policies", "attributes")
        return Grantor(
            tenant = TenantId(node.required("tenant").text()),
            id = PrincipalId(node.required("id").text()),
            memberships = MembershipClaims.parse(
                node.get("roles")?.strings().orEmpty(),
                node.get("policies")?.strings().orEmpty()
            ),
            attributes = node.get("attributes")?.fields().orEmpty().mapValues { it.value.text() },
        )
    }

    private fun document(node: JsonNode, tenant: TenantId?): GrantDocument {
        node.only("policies", "bindings")
        val bindings = node.get("bindings")?.values().orEmpty()
        return GrantDocument(
            policies = node.get("policies")?.values()?.map(::policy).orEmpty(),
            bindings = bindings.map { binding(it, tenant) },
            protectedBindings = bindings.filter { it.get("protected")?.bool() == true }
                .mapTo(HashSet()) { BindingId(it.required("id").text()) },
        )
    }

    private fun policy(node: JsonNode): GrantPolicy {
        node.only("id", "attachable", "protected", "condition", "statements")
        return GrantPolicy(
            id = PolicyId(node.required("id").text()),
            statements = node.required("statements").values().map(::statement),
            condition = node.get("condition")?.let(ConditionReader::read),
            attachable = node.get("attachable")?.bool() ?: false,
            protected = node.get("protected")?.bool() ?: false,
        )
    }

    private fun statement(node: JsonNode): GrantStatement {
        node.only("name", "effect", "action", "resource", "condition")
        val effect = when (val effect = node.required("effect").text()) {
            "allow" -> Effect.ALLOW
            "deny" -> Effect.DENY
            else -> throw ScenarioFormatException("Effect is allow or deny, not $effect")
        }
        return GrantStatement(
            effect = effect,
            actions = node.required("action").strings().map(ActionPattern::parse),
            resources = node.get("resource")?.strings()?.map { ResourcePattern.parse(it) } ?: listOf(ResourcePattern.AnyResource),
            condition = node.get("condition")?.let(ConditionReader::read),
            name = node.get("name")?.text() ?: "",
        )
    }

    private fun binding(node: JsonNode, tenant: TenantId?): Binding {
        node.only("id", "subject", "app", "space", "policy", "expiresAt", "protected")
        return Binding(
            id = BindingId(node.required("id").text()),
            subject = subject(node.required("subject"), tenant),
            scope = BindingScope(
                match(node.required("app").text(), ::AppId),
                match(node.required("space").text(), ::SpaceId)
            ),
            policy = PolicyId(node.required("policy").text()),
            expiresAt = node.get("expiresAt")?.instant(),
        )
    }

    private fun subject(node: JsonNode, tenant: TenantId?): SubjectRef {
        if (node.isString) {
            if (node.asString() != Match.WILDCARD) throw ScenarioFormatException("A subject string is *, not $node")
            return SubjectRef.Anyone
        }
        node.only("principal", "role", "tenant")
        val subjectTenant = node.get("tenant")?.text()?.let(::TenantId) ?: tenant
        node.get("principal")?.let {
            val principalTenant = subjectTenant ?: throw ScenarioFormatException("A principal names its tenant: $node")
            return SubjectRef.Principal(principalTenant, PrincipalId(it.text()))
        }
        return SubjectRef.Role(subjectTenant, RoleId(node.required("role").text()))
    }

    private fun <T> match(value: String, of: (String) -> T): Match<T> =
        if (value == Match.WILDCARD) Match.Any else Match.Exactly(of(value))
}
