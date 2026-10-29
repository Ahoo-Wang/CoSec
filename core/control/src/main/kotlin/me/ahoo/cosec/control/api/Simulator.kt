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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpAttributes
import me.ahoo.cosec.runtime.snapshot.MissingPlatformException
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

/**
 * A request to decide without making it (RFC 0007 §10.3): who asks, with which memberships, in which scope, for which
 * action and resource, with which headers and request attributes. It describes the principal, so any principal of the
 * caller's reach can be simulated.
 */
data class SimulatedRequest(
    val tenant: TenantId,
    val principal: String?,
    val roles: List<String> = emptyList(),
    val policies: List<String> = emptyList(),
    val app: String = "",
    val space: String = "",
    val action: Action,
    val resource: Resource,
    val remoteIp: String = "",
    val time: Long,
    /** The request's headers by name, each with its values in order: what `http.*` conditions read. */
    val headers: Map<String, List<String>> = emptyMap(),
    /** What the host knows besides HTTP, such as a gateway's route id: what `request.attributes.*` conditions read. */
    val requestAttributes: Map<String, String> = emptyMap(),
    /**
     * False for a credential with a role cap: what is bound to its principal by name grants it nothing, and its DENYs
     * still apply (RFC 0009 §4.2, RC1-1). A token by default.
     */
    val principalBindings: Boolean = true,
    /** False for an API key or any credential but CoSec's own token: break-glass does not apply. A token by default. */
    val breakGlass: Boolean = true,
)

/** The revisions of the documents to decide on (RFC 0006 §9.2's `snapshotVersion`): what an audit event records. */
data class SnapshotVersion(val platform: Long, val app: Long?, val tenant: Long?)

/** The documents a decision read, compiled, with their revisions: what [Simulator.evaluate] decides on. */
class Documents(val platform: PolicyDocument?, val app: PolicyDocument?, val tenant: PolicyDocument?)

/**
 * The control plane's decisions (RFC 0007 §10.3, Q22, CTL-3): the enforcing flow's parts, not a second evaluator. The
 * scope is resolved by [ScopeResolver], the documents are compiled by the data plane's [PolicyDocumentCompiler] with
 * the platform's limits and attributes and the host's [attributes], into the view enforcement would read, and [engine]
 * is the data plane's `Engine.evaluate`, at full trace.
 */
class Simulator(
    private val engine: FlatEngine = CoSecFlatEngine,
    /** The host's `AttributeProvider` catalog, which enforcement compiles against. */
    private val attributes: AttributeCatalog = AttributeCatalog.NONE,
    /** The headers the host reads credentials from besides the fixed ones, as enforcement compiles with them. */
    private val credentialHeaders: Set<String> = emptySet(),
) {
    /** The scope [request] resolves to, or the rejection that decides it, as the pipeline resolves a host request. */
    fun scope(request: SimulatedRequest): ScopeResolution {
        val memberships = MembershipClaims.parse(request.roles, request.policies)
        val subject = ScopeSubject(request.tenant, request.principal != null, memberships)
        return ScopeResolver.resolve(subject, DeclaredScope(request.app, request.space), HostScope(null))
    }

    fun evaluate(request: SimulatedRequest, scope: Scope, documents: Documents): FlatEvaluation {
        val memberships = MembershipClaims.parse(request.roles, request.policies)
        val subject = Subject(
            id = request.principal ?: ANONYMOUS,
            tenantId = scope.tenant.value,
            authenticated = request.principal != null,
            roles = emptySet(),
            policies = emptySet(),
            attributes = emptyMap(),
            root = false,
            // As the credential would be (RFC 0009 §4.2): a key's cap drops principal bindings, a key gets no break-glass.
            principalBindings = request.principalBindings,
            breakGlass = request.breakGlass,
        )
        val authz = AuthzRequest(
            subject = subject,
            scope = DeclaredRequestScope.declared(scope.tenant.value, scope.app.value, scope.space.value),
            action = request.action,
            resource = request.resource,
            environment = Environment(request.remoteIp, REQUEST_ID, "", false),
        )
        return engine.evaluate(
            FlatRequest(
                authz,
                scope,
                memberships,
                request.time,
                HttpAttributes(Headers.of(request.headers), request.requestAttributes),
            ),
            view(scope, documents),
            TraceLevel.FULL
        )
    }

    /** The documents of [scope]: the platform's, the tenant's, and the app's when the scope has roles in an app. */
    fun keys(scope: Scope): List<SnapshotKey> = listOfNotNull(
        SnapshotKey.Platform,
        SnapshotKey.Tenant(scope.tenant.value),
        scope.app.takeIf { scope.roles.isNotEmpty() && it.value.isNotEmpty() }?.let { SnapshotKey.App(it.value) },
    )

    /**
     * The view enforcement would read (`DocumentSnapshotViews` with `platformRequired`): an absent platform document is
     * unresolved, not empty; a document that does not compile is unresolved, so the decision is indeterminate unless a
     * DENY matches, as on the data plane, rather than an error of the whole call.
     */
    private fun view(scope: Scope, documents: Documents): SnapshotView {
        val unresolved = ArrayList<Cause>(0)
        val compiler = compiler(documents.platform)
        val platform = documents.platform?.let { compiled(compiler, it, unresolved) } ?: run {
            if (documents.platform == null) {
                unresolved += Cause.SourceUnavailable(DocumentKind.PLATFORM, MissingPlatformException())
            }
            CompiledDocument.platform(emptyList(), emptyList())
        }
        val app = scope.app.takeIf { scope.roles.isNotEmpty() && it.value.isNotEmpty() }
        return SnapshotView(
            platform,
            app?.let { id ->
                documentOf(
                    documents.app,
                    compiler,
                    unresolved
                ) { CompiledDocument.app(AppId(id.value), emptyList(), emptyList()) }
            },
            documentOf(documents.tenant, compiler, unresolved) {
                CompiledDocument.tenant(scope.tenant, emptyList(), emptyList())
            },
            unresolved,
            version = ViewVersion(
                documents.platform?.revision ?: 0,
                app?.let {
                    documents.app?.revision ?: 0
                },
                documents.tenant?.revision ?: 0
            ),
        )
    }

    /**
     * The compiler of [platform] as the data plane's `PlatformCompiler` makes it: its limits, and the attributes it
     * declares ahead of the host's providers ([attributes]).
     */
    private fun compiler(platform: PolicyDocument?): PolicyDocumentCompiler =
        PolicyDocumentCompiler.forPlatform(platform, attributes, credentialHeaders = credentialHeaders)

    /** [document] compiled, [empty] when none is published, or null (unresolved) when it does not compile. */
    private fun documentOf(
        document: PolicyDocument?,
        compiler: PolicyDocumentCompiler,
        unresolved: MutableList<Cause>,
        empty: () -> CompiledDocument,
    ): CompiledDocument? = if (document == null) empty() else compiled(compiler, document, unresolved)

    @Suppress("TooGenericExceptionCaught")
    private fun compiled(
        compiler: PolicyDocumentCompiler,
        document: PolicyDocument,
        unresolved: MutableList<Cause>,
    ): CompiledDocument? = try {
        compiler.compile(document)
    } catch (e: Exception) {
        unresolved += Cause.SourceUnavailable(document.kind, e)
        null
    }

    companion object {
        private const val ANONYMOUS = "(0)"
        private const val REQUEST_ID = "simulated"
    }
}

/** A statement as a decision reports it to a caller: by policy, index and the binding it applied through. */
data class StatementView(
    val policy: String,
    val statement: Int,
    val name: String,
    val via: String,
    val document: String?
)

/**
 * A decision as the control plane answers it (RFC 0007 §10.3): its kind, and the statement that made it only when the
 * caller may read the document that statement came through. An indeterminate decision names its cause's type, never
 * its message.
 */
data class DecisionView(val decision: String, val by: StatementView?, val cause: String?, val redacted: Boolean)

/** One trace entry, from a document the caller may read; [redacted] counts the entries left out. */
data class TraceView(val entries: List<TraceEntryView>, val redacted: Int)

data class TraceEntryView(val statement: StatementView, val effect: String, val outcome: String)

/**
 * What a caller may see of a decision and its trace: entries of documents it may read (RFC 0007 §10.3). A statement
 * reached through an attachment may come from the tenant or the platform, so it is shown only when both are readable.
 */
internal class Redaction(private val readable: Set<DocumentKind>) {
    fun decision(decision: Decision): DecisionView = when (decision) {
        is Decision.Permit -> view("Permit", decision.by, null)
        is Decision.Deny -> view("Deny", decision.by, null)
        is Decision.Indeterminate -> DecisionView(
            "Indeterminate",
            null,
            decision.cause::class.simpleName,
            redacted = false
        )
    }

    fun trace(evaluation: FlatEvaluation): TraceView? {
        val trace = evaluation.trace ?: return null
        val visible = trace.candidates.filter { visible(it.ref.via) }
        return TraceView(
            visible.map { TraceEntryView(statement(it.ref), it.effect.name, it.outcome.name) },
            trace.candidates.size - visible.size,
        )
    }

    private fun view(kind: String, by: StatementRef?, cause: String?): DecisionView {
        val policy = by as? StatementRef.Policy
        return when {
            by == null -> DecisionView(kind, null, cause, redacted = false)
            policy != null && visible(policy.via) -> DecisionView(kind, statement(policy), cause, redacted = false)
            else -> DecisionView(kind, null, cause, redacted = true)
        }
    }

    private fun visible(via: BindingRef): Boolean = when (via) {
        is BindingRef.Bound -> via.document in readable
        is BindingRef.Attached -> DocumentKind.TENANT in readable && DocumentKind.PLATFORM in readable
        else -> DocumentKind.PLATFORM in readable
    }

    private fun statement(ref: StatementRef.Policy): StatementView {
        val (via, document) = when (val binding = ref.via) {
            is BindingRef.Bound -> binding.id.value to binding.document.name.lowercase()
            is BindingRef.Attached -> "attached:${binding.policy.value}" to null
            else -> binding.toString() to null
        }
        return StatementView(ref.policy.value, ref.index, ref.name, via, document)
    }
}
