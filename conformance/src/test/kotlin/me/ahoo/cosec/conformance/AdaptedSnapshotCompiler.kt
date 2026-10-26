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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.CompiledPolicy
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
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
import me.ahoo.cosec.model.condition.CheckedCondition
import tools.jackson.databind.JsonNode

/** The documents of a scenario, as written. */
class ScenarioDocuments(val platform: JsonNode?, val apps: Map<String, JsonNode>, val tenants: Map<String, JsonNode>)

/** Every document of a scenario, compiled. */
class CompiledSnapshot(
    val platform: CompiledDocument,
    val apps: Map<String, CompiledDocument>,
    val tenants: Map<String, CompiledDocument>,
)

/**
 * Turns scenario documents into what the engine evaluates. The one place that knows how statements are represented,
 * so the engine runner follows a change of representation by swapping it: RFC 0004 PR-4 brings the snapshot
 * compiler and the compiled AST statements of RFC 0005 PR-4, and then that compiler replaces [AdaptedSnapshotCompiler].
 */
fun interface SnapshotCompiler {
    /** Throws [DocumentException] when a document does not compile. */
    fun compile(documents: ScenarioDocuments): CompiledSnapshot
}

/**
 * Compiles the documents onto what `main` evaluates today: [CompiledDocument]s whose statements are [Rule]s (RFC 0002
 * adapter contracts). Each statement is its action patterns, resource patterns and checked condition (RFC 0005 §5.1);
 * it reads the request from the [RequestFacts] the runner puts in the request.
 *
 * Document syntax (RFC 0004 §7.1, statements as RFC 0005 §5.1):
 *
 * ```jsonc
 * { "policies": [ { "id": "p", "attachable": false, "condition": { ... },
 *                   "statements": [ { "name": "s", "effect": "deny", "action": "order:*", "resource": "order/{id}",
 *                                     "condition": { ... } } ] } ],
 *   "bindings": [ { "id": "b", "subject": "*" | { "principal": "alice", "tenant": "acme" } | { "role": "editor" },
 *                   "app": "*", "space": "*", "policy": "p", "expiresAt": "...", "breakGlass": false } ] }
 * ```
 *
 * One addition for the suite only: `"fault": "message"` on a statement or a policy makes evaluating that statement, or
 * that policy's condition, throw. It stands for any failing input (a contract violation, an external attribute that
 * cannot be fetched) so the combination rules can be checked without depending on how a failure arises.
 */
object AdaptedSnapshotCompiler : SnapshotCompiler {
    override fun compile(documents: ScenarioDocuments): CompiledSnapshot =
        compiling("platform") {
            CompiledSnapshot(
                platform = documents.platform.let { CompiledDocument.platform(policies(it), bindings(it, null)) },
                apps = documents.apps.mapValues { (app, node) ->
                    compiling("app $app") { CompiledDocument.app(AppId(app), policies(node), bindings(node, null)) }
                },
                tenants = documents.tenants.mapValues { (tenant, node) ->
                    compiling("tenant $tenant") {
                        CompiledDocument.tenant(TenantId(tenant), policies(node), bindings(node, TenantId(tenant)))
                    }
                },
            )
        }

    /** Compile errors of the engine and the model are [IllegalArgumentException]s. */
    private fun <T> compiling(document: String, block: () -> T): T =
        try {
            block()
        } catch (error: IllegalArgumentException) {
            throw DocumentException("The $document document does not compile: ${error.message}", error)
        }

    private fun policies(document: JsonNode?): List<CompiledPolicy> {
        document?.only("policies", "bindings")
        return document?.get("policies")?.values()?.map(::policy).orEmpty()
    }

    private fun policy(node: JsonNode): CompiledPolicy {
        node.only("id", "attachable", "condition", "fault", "statements")
        val id = node.required("id").text()
        val condition = node.get("condition")?.let(ConditionReader::check)
        val fault = node.get("fault")?.text()
        val guard = if (condition != null || fault != null) PolicyCondition(condition, fault) else null
        val statements = node.required("statements").values().mapIndexed(::statement)
        return CompiledPolicy(PolicyId(id), guard, statements, node.get("attachable")?.bool() ?: false)
    }

    private fun statement(index: Int, node: JsonNode): Rule {
        node.only("name", "effect", "action", "resource", "condition", "fault")
        val effect = when (val effect = node.required("effect").text()) {
            "allow" -> Effect.ALLOW
            "deny" -> Effect.DENY
            else -> throw ScenarioFormatException("Effect is allow or deny, not $effect")
        }
        return Statement(
            effect = effect,
            index = index,
            name = node.get("name")?.text() ?: "",
            actions = node.required("action").strings().map(ActionPattern::parse),
            resources = node.get("resource")?.strings()?.map { ResourcePattern.parse(it) },
            condition = node.get("condition")?.let(ConditionReader::check),
            fault = node.get("fault")?.text(),
        )
    }

    private fun bindings(document: JsonNode?, tenant: TenantId?): List<Binding> =
        document?.get("bindings")?.values()?.map { binding(it, tenant) }.orEmpty()

    private fun binding(node: JsonNode, tenant: TenantId?): Binding {
        node.only("id", "subject", "app", "space", "policy", "expiresAt", "breakGlass")
        return Binding(
            id = BindingId(node.required("id").text()),
            subject = subject(node.required("subject"), tenant),
            scope = BindingScope(
                app = match(node.required("app").text(), ::AppId),
                space = match(node.required("space").text(), ::SpaceId),
            ),
            policy = PolicyId(node.required("policy").text()),
            expiresAt = node.get("expiresAt")?.instant(),
            breakGlass = node.get("breakGlass")?.bool() ?: false,
        )
    }

    /** A principal or role of a tenant document is in that tenant unless it says otherwise (RFC 0004 §4.2). */
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

/** A failure injected by `"fault"`. */
class ScenarioFault(message: String) : RuntimeException(message)

private class PolicyCondition(private val condition: CheckedCondition?, private val fault: String?) : Guard {
    override fun matches(request: AuthzRequest): Boolean {
        fault?.let { throw ScenarioFault(it) }
        return condition!!.evaluate(request.facts.input(null))
    }
}

private class Statement(
    override val effect: Effect,
    override val index: Int,
    override val name: String,
    private val actions: List<ActionPattern>,
    /** Null for any resource. */
    private val resources: List<ResourcePattern>?,
    private val condition: CheckedCondition?,
    private val fault: String?,
) : Rule {
    override val id: String? = null

    override fun match(request: AuthzRequest): RuleMatch {
        fault?.let { throw ScenarioFault(it) }
        if (actions.none { it.matches(request.action) }) {
            return RuleMatch.ACTION_MISS
        }
        val captures: Captures? = if (resources == null) {
            Captures.NONE
        } else {
            val target = ResourceTarget(request.resource)
            resources.firstNotNullOfOrNull { it.match(target) }
        }
        if (captures == null) {
            return RuleMatch.ACTION_MISS
        }
        if (condition != null && !condition.evaluate(request.facts.input(captures))) {
            return RuleMatch.CONDITION_MISS
        }
        return RuleMatch.MATCHED
    }
}

private val AuthzRequest.facts: RequestFacts get() = host as RequestFacts
