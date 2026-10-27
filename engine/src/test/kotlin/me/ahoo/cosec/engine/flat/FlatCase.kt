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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.MatcherException
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import kotlin.random.Random

/** Compared by the policy's identity: two documents may define policies of the same id. */
class Candidate(val policy: CompiledPolicy, val via: BindingRef) {
    override fun equals(other: Any?): Boolean = other is Candidate && other.policy === policy && other.via == via

    override fun hashCode(): Int = via.hashCode()

    override fun toString(): String = "${policy.id} via $via"
}

/** A document before compiling, so the oracle can scan its bindings. */
class RawDocument(val policies: List<CompiledPolicy>, val bindings: List<Binding>) {
    fun without(policyId: String) = RawDocument(policies.filter { it.id != policyId }, bindings)

    fun with(policy: CompiledPolicy, binding: Binding? = null) =
        RawDocument(policies + policy, if (binding == null) bindings else bindings + binding)
}

/** A random snapshot and request. The app and tenant documents are those of the request's scope. */
class FlatCase(
    val fixture: FlatFixture,
    val platform: RawDocument,
    val app: RawDocument,
    val tenant: RawDocument,
    val appLoaded: Boolean,
    val tenantLoaded: Boolean,
    val unresolved: List<Cause>,
    val request: FlatRequest,
    /** The platform's boundary policies (RFC 0007 §7.6), its default and the tenant's own. */
    val boundaries: List<CompiledPolicy> = emptyList(),
    val defaultBoundary: String? = null,
    val tenantBoundary: String? = null,
) {
    val platformDocument = CompiledDocument.platform(platform.policies, platform.bindings, emptyList(), boundaries, defaultBoundary)
    val appDocument = CompiledDocument.app(request.scope.app, app.policies, app.bindings)
    val tenantDocument =
        CompiledDocument.tenant(request.scope.tenant, tenant.policies, tenant.bindings, emptyList(), tenantBoundary)

    fun view(tenant: CompiledDocument? = tenantDocument.takeIf { tenantLoaded }) =
        SnapshotView(platformDocument, appDocument.takeIf { appLoaded }, tenant, unresolved)

    fun evaluate(level: TraceLevel = TraceLevel.OFF) = CoSecFlatEngine.evaluate(request, view(), level)

    fun copy(
        platform: RawDocument = this.platform,
        app: RawDocument = this.app,
        tenant: RawDocument = this.tenant,
        appLoaded: Boolean = this.appLoaded,
        tenantLoaded: Boolean = this.tenantLoaded,
        unresolved: List<Cause> = this.unresolved,
        request: FlatRequest = this.request,
        boundaries: List<CompiledPolicy> = this.boundaries,
        defaultBoundary: String? = this.defaultBoundary,
        tenantBoundary: String? = this.tenantBoundary,
    ) = FlatCase(
        fixture, platform, app, tenant, appLoaded, tenantLoaded, unresolved, request,
        boundaries, defaultBoundary, tenantBoundary,
    )

    private val attachmentIds: List<String> get() = request.memberships.attachments.map { it.value }

    /** One DENY that applies to the request and always matches, through a random route. */
    fun withMatchingDeny(random: Random): FlatCase {
        val deny = fixture.policy("added-deny", deny(), attachable = true)
        val subject = request.request.subject
        val scope = request.scope
        val anyonePlatform = { copy(platform = platform.with(deny, anyone("added", deny.id))) }
        return when (random.nextInt(if (subject.authenticated) 5 else 2)) {
            0 -> anyonePlatform()
            1 -> copy(tenant = tenant.with(deny, anyone("added", deny.id)), tenantLoaded = true)
            2 -> copy(platform = platform.with(deny, principal("added", deny.id, scope.tenant.value, subject.id)))
            3 -> copy(platform = platform.with(deny), request = withAttachments(request, attachmentIds + deny.id))
            else -> if (scope.roles.isEmpty() || scope.app.value.isEmpty()) {
                anyonePlatform()
            } else {
                val role = role("added", scope.roles.random(random).value, deny.id, scope.app.value, scope.space.value)
                copy(app = app.with(deny, role), appLoaded = true)
            }
        }
    }

    /** The case with a document unavailable, or an attached policy gone. */
    fun degradations(random: Random): List<FlatCase> {
        val error = MatcherException("unavailable")
        val cases = mutableListOf(
            copy(tenantLoaded = false),
            copy(appLoaded = false),
            copy(unresolved = unresolved + Cause.SourceUnavailable(DocumentKind.entries.random(random), error)),
            copy(request = withAttachments(request, attachmentIds + "gone")),
        )
        // A platform binding must name a platform policy, so only an attachment nothing in the platform binds goes.
        val removable = attachmentIds.filter { id -> platform.bindings.none { it.policy.value == id } }
        if (removable.isNotEmpty()) {
            val gone = removable.random(random)
            cases += copy(platform = platform.without(gone), tenant = tenant.without(gone))
        }
        return cases
    }

    fun describe(): String =
        buildString {
            val subject = request.request.subject
            val scope = request.scope
            append("request: ${subject.id}@${scope.tenant.value} authenticated=${subject.authenticated} ")
            append("app=${scope.app.value} space=${scope.space.value} roles=${scope.roles.map { it.value }} ")
            appendLine("attachments=$attachmentIds blocked=${request.request.environment.blocked}")
            appendLine("loaded: app=$appLoaded tenant=$tenantLoaded unresolved=$unresolved")
            appendLine("boundaries: default=$defaultBoundary tenant=$tenantBoundary")
            boundaries.forEach { appendLine("  boundary ${describe(it)}") }
            for ((name, document) in listOf("platform" to platform, "app" to app, "tenant" to tenant)) {
                appendLine("$name:")
                document.policies.forEach { appendLine("  policy ${describe(it)}") }
                document.bindings.forEach { appendLine("  binding ${describe(it)}") }
            }
        }

    private fun describe(policy: CompiledPolicy): String {
        val spec = fixture.spec(policy) ?: return policy.id
        val guard = spec.guard?.let { " guard=${it.behavior}" }.orEmpty()
        val statements = spec.statements.map { (effect, behavior) -> "$effect:$behavior" }
        return "${policy.id} attachable=${policy.attachable}$guard $statements"
    }

    private fun describe(binding: Binding): String =
        with(binding) {
            "${id.value}: $subject app=${scope.app} space=${scope.space} -> ${policy.value} " +
                "expiresAt=$expiresAt breakGlass=$breakGlass"
        }
}

fun withAttachments(request: FlatRequest, attachments: List<String>): FlatRequest {
    val memberships = Memberships(request.memberships.source, request.memberships.roles, attachments.map(::PolicyId))
    return FlatRequest(request.request, request.scope, memberships, request.time, request.attributes)
}
