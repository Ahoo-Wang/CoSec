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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
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
import me.ahoo.cosec.runtime.snapshot.ViewDocuments
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.atomic.AtomicLong

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
 * A document to decide on and, for a stored one (a head, or a retained revision), its [entry] in the [DocumentCache]:
 * then it is compiled once for its content (RC1-41). A candidate has none, and is compiled once per call.
 */
internal class Held(val document: PolicyDocument, val entry: DocumentCache.Entry?) {
    companion object {
        fun of(document: PolicyDocument?): Held? = document?.let { Held(it, null) }
    }
}

/** The documents a decision reads, as [Held]. */
internal class HeldDocuments(val platform: Held?, val app: Held?, val tenant: Held?)

/**
 * The control plane's decisions (RFC 0007 §10.3, Q22, CTL-3): the enforcing flow's parts, not a second evaluator. The
 * scope is resolved by [ScopeResolver], the documents are compiled by the data plane's [PolicyDocumentCompiler] with
 * the platform's limits and attributes and the host's [attributes], into the view enforcement would read, and [engine]
 * is the data plane's `Engine.evaluate`, at full trace. A stored document is compiled once for its content, in a cache
 * `simulate`, `explain` and `analyze` share (RC1-41).
 */
class Simulator(
    private val engine: FlatEngine = CoSecFlatEngine,
    /** The host's `AttributeProvider` catalog, which enforcement compiles against. */
    private val attributes: AttributeCatalog = AttributeCatalog.NONE,
    /** The headers the host reads credentials from besides the fixed ones, as enforcement compiles with them. */
    private val credentialHeaders: Set<String> = emptySet(),
) {
    /** The documents decoded and compiled for this simulator's compilers. */
    internal val cache = DocumentCache()

    /** Every compilation this simulator made, cached or not; for tests. */
    internal val compilations = AtomicLong()

    /**
     * The scope [request] resolves to, or the rejection that decides it, as the pipeline resolves a request. [request]'s
     * app is the one its credential declares (RFC 0012 §3.5) on a host of that app, which is also where an anonymous
     * request gets its app.
     */
    fun scope(request: SimulatedRequest): ScopeResolution {
        val app = request.app.ifEmpty { null }
        val host = HostScope(app?.takeIf(ScopeResolver::isValidAppId)?.let(::AppId))
        return ScopeResolver.resolve(subject(request, app), request.space, host)
    }

    /**
     * The tenant [request] is decided in, as [ScopeResolver] decides it, whatever its scope (RC1-40): the principal's,
     * or [TenantId.DEFAULT] for a request without one. With no app and no space it always resolves, so this is the
     * resolver's own answer, not a copy of its rule.
     */
    internal fun tenantOf(request: SimulatedRequest): TenantId =
        (ScopeResolver.resolve(subject(request, null), "", HostScope(null)) as ScopeResolution.Resolved).scope.tenant

    private fun subject(request: SimulatedRequest, app: String?): ScopeSubject =
        ScopeSubject(
            request.tenant,
            request.principal != null,
            MembershipClaims.parse(request.roles, request.policies),
            app
        )

    fun evaluate(request: SimulatedRequest, scope: Scope, documents: Documents): FlatEvaluation =
        decide(
            request,
            scope,
            HeldDocuments(Held.of(documents.platform), Held.of(documents.app), Held.of(documents.tenant)),
        ).evaluation

    /**
     * [evaluate], with what its view read (R2-5 of #1194's review): [keys] says which documents to fetch from the raw
     * tenant document, but when that document does not compile the view, as enforcement's, reads no app document for a
     * roleless request. What a caller reports or checks against is what the view read.
     *
     * A request that sends a header the view's documents read with different values is refused 400, as enforcement
     * refuses it before evaluating (`PIP-12`): the answer names no header, so it does not tell which document reads it.
     */
    internal fun decide(request: SimulatedRequest, scope: Scope, documents: HeldDocuments): Decided {
        val memberships = MembershipClaims.parse(request.roles, request.policies)
        val subject = Subject(
            id = request.principal ?: ANONYMOUS,
            authenticated = request.principal != null,
            attributes = emptyMap(),
            // As the credential would be (RFC 0009 §4.2): a key's cap drops principal bindings, a key gets no break-glass.
            principalBindings = request.principalBindings,
            breakGlass = request.breakGlass,
        )
        val authz = AuthzRequest(
            subject = subject,
            action = request.action,
            resource = request.resource,
            environment = Environment(request.remoteIp, REQUEST_ID, "", false),
        )
        val view = view(scope, documents)
        val http = HttpAttributes(Headers.of(request.headers), request.requestAttributes)
        if (http.ambiguousHeader(view) != null) {
            throw ChangeRejectedException(BAD_REQUEST, AMBIGUOUS_HEADER)
        }
        val evaluation = engine.evaluate(
            FlatRequest(authz, scope, memberships, request.time, http),
            view,
            TraceLevel.FULL
        )
        return Decided(evaluation, scope, view.version!!)
    }

    /** An evaluation, and the documents its view read with their revisions. */
    internal class Decided(val evaluation: FlatEvaluation, scope: Scope, version: ViewVersion) {
        /** The revisions the view read: an app's only when it took the app's document (its revision, 0 for none). */
        val version = SnapshotVersion(version.platform, version.app, version.tenant ?: 0)

        /** The documents the view read: the platform's, the tenant's, and the app's when it took it. */
        val read: List<SnapshotKey> = listOfNotNull(
            SnapshotKey.Platform,
            SnapshotKey.Tenant(scope.tenant.value),
            version.app?.let { SnapshotKey.App(scope.app.value) },
        )
    }

    /**
     * The documents a request in [scope] reads, by enforcement's rule ([ViewDocuments.appOf], RC1-17): the platform's,
     * the tenant's, and the app's when the scope has roles in an app or [tenant], the tenant's document the request is
     * decided with, binds one of the app's permission policies to `Anyone` or to a principal.
     */
    fun keys(scope: Scope, tenant: PolicyDocument?): List<SnapshotKey> {
        val app = ViewDocuments.appOf(scope) { tenant != null && it in PolicyDocumentCompiler.boundApps(tenant) }
        return listOfNotNull(
            SnapshotKey.Platform,
            SnapshotKey.Tenant(scope.tenant.value),
            app?.let { SnapshotKey.App(it.value) },
        )
    }

    /**
     * The view enforcement would read (`DocumentSnapshotViews` with `platformRequired`): an absent platform document is
     * unresolved, not empty; a document that does not compile is unresolved, so the decision is indeterminate unless a
     * DENY matches, as on the data plane, rather than an error of the whole call.
     */
    private fun view(scope: Scope, documents: HeldDocuments): SnapshotView {
        val unresolved = ArrayList<Cause>(0)
        val platformDocument = documents.platform?.document
        val compiler = Compilation(compiler(platformDocument), documents.platform)
        val platform = documents.platform?.let { compiled(compiler, it, unresolved) } ?: run {
            if (documents.platform == null) {
                unresolved += Cause.SourceUnavailable(DocumentKind.PLATFORM, MissingPlatformException())
            }
            CompiledDocument.platform(emptyList(), emptyList())
        }
        // The tenant's document decides whether the app's is read (RC1-17); its causes follow the app's.
        val tenantCauses = ArrayList<Cause>(0)
        val tenant = documentOf(documents.tenant, compiler, tenantCauses) {
            CompiledDocument.tenant(scope.tenant, emptyList(), emptyList())
        }
        val app = ViewDocuments.appOf(scope) { tenant?.needsApp(it) == true }
        val appDocument = app?.let { id ->
            documentOf(
                documents.app,
                compiler,
                unresolved
            ) { CompiledDocument.app(AppId(id.value), emptyList(), emptyList()) }
        }
        unresolved += tenantCauses
        return SnapshotView(
            platform,
            appDocument,
            tenant,
            unresolved,
            version = ViewVersion(
                platformDocument?.revision ?: 0,
                app?.let {
                    documents.app?.document?.revision ?: 0
                },
                documents.tenant?.document?.revision ?: 0
            ),
        )
    }

    /**
     * The compiler of the platform document [platform] (null: none) as the data plane's `PlatformCompiler` makes it:
     * its limits, and the attributes it declares ahead of the host's providers ([attributes]). What it compiles is
     * cached by [platform]'s digest when it is a stored document; with a candidate platform, nothing is.
     */
    private class Compilation(val compiler: PolicyDocumentCompiler, platform: Held?) {
        val cached: Boolean = platform == null || platform.entry != null
        val platformDigest = platform?.entry?.digest
    }

    private fun compiler(platform: PolicyDocument?): PolicyDocumentCompiler =
        PolicyDocumentCompiler.forPlatform(platform, attributes, credentialHeaders = credentialHeaders)

    /** [document] compiled, [empty] when none is published, or null (unresolved) when it does not compile. */
    private fun documentOf(
        document: Held?,
        compilation: Compilation,
        unresolved: MutableList<Cause>,
        empty: () -> CompiledDocument,
    ): CompiledDocument? = if (document == null) empty() else compiled(compilation, document, unresolved)

    private fun compiled(compilation: Compilation, document: Held, unresolved: MutableList<Cause>): CompiledDocument? {
        val compile = {
            compilations.incrementAndGet()
            compilation.compiler.compile(document.document)
        }
        val entry = document.entry
        val result = if (entry != null && compilation.cached) {
            entry.compiled(compilation.platformDigest, compile)
        } else {
            compiling(compile)
        }
        return result.getOrElse {
            unresolved += Cause.SourceUnavailable(document.document.kind, it as Exception)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compiling(compile: () -> CompiledDocument): Result<CompiledDocument> = try {
        Result.success(compile())
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val ANONYMOUS = "(0)"
        private const val REQUEST_ID = "simulated"
        private const val BAD_REQUEST = 400
        private const val AMBIGUOUS_HEADER = "A header the documents of this request's view read arrived with different " +
            "values: enforcement refuses such a request (PIP-12)."
    }
}

/**
 * Whether [candidate] decides differently from [enforced]. Everything a decision says counts, but a failure counts by
 * what it is, its type and message: each side compiles on its own, so the same failure is two exceptions (#1253).
 */
internal fun differs(enforced: Decision, candidate: Decision): Boolean = comparable(enforced) != comparable(candidate)

private fun comparable(decision: Decision): Any = when (decision) {
    is Decision.Indeterminate -> when (val cause = decision.cause) {
        is Cause.MatcherFailed -> listOf(cause::class, cause.at, cause.guard, failure(cause.error))
        is Cause.SourceUnavailable -> listOf(cause::class, cause.document, failure(cause.error))
        is Cause.BoundaryUnavailable -> listOf(cause::class, cause.boundary, failure(cause.error))
        is Cause.MembershipUnavailable -> listOf(cause::class, failure(cause.error))
        else -> cause
    }

    else -> decision
}

private fun failure(error: Throwable): Pair<Class<*>, String?> = error.javaClass to error.message

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
