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

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.CompiledPolicy
import me.ahoo.cosec.engine.flat.PolicyCompiler
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
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
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
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
 * Compiles the documents with the engine's [PolicyCompiler] (RFC 0005 §6.1): statements are their action patterns,
 * resource patterns and conditions, compiled. The snapshot compiler of RFC 0004 PR-4 replaces this one.
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
 * One addition for the suite only: `"fault": "message"` on a statement or a policy makes evaluating that statement's
 * condition, or that policy's condition, throw. It stands for any failing input (a contract violation, an external
 * attribute that cannot be fetched) so the combination rules can be checked without depending on how a failure
 * arises. It is a condition that reads the host attribute `request.attributes.<FAULT><message>` first, which
 * [RequestFacts.host] answers by throwing [ScenarioFault].
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

    private val compiler = PolicyCompiler()

    private fun policy(node: JsonNode): CompiledPolicy {
        node.only("id", "attachable", "condition", "fault", "statements")
        val id = node.required("id").text()
        val condition = faulting(node.get("fault")?.text(), node.get("condition")?.let(ConditionReader::read))
        val statements = node.required("statements").values().map(::statement)
        return compiler.compile(PolicyId(id), condition, statements, node.get("attachable")?.bool() ?: false)
    }

    private fun statement(node: JsonNode): StatementSource {
        node.only("name", "effect", "action", "resource", "condition", "fault")
        val effect = when (val effect = node.required("effect").text()) {
            "allow" -> Effect.ALLOW
            "deny" -> Effect.DENY
            else -> throw ScenarioFormatException("Effect is allow or deny, not $effect")
        }
        return StatementSource(
            name = node.get("name")?.text() ?: "",
            effect = effect,
            actions = node.required("action").strings(),
            resources = node.get("resource")?.strings()?.map { ResourceSource(it) }.orEmpty(),
            condition = faulting(node.get("fault")?.text(), node.get("condition")?.let(ConditionReader::read)),
        )
    }

    /** [condition], preceded by a read that throws [ScenarioFault] with [fault]. */
    private fun faulting(fault: String?, condition: Condition?): Condition? {
        if (fault == null) {
            return condition
        }
        val probe = Condition.Eq("request.attributes.$FAULT$fault", Literal.Str(""))
        return Condition.And(listOfNotNull(probe, condition))
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

/** The prefix of the request attribute a `"fault"` reads. */
const val FAULT: String = "cosec.fault:"
