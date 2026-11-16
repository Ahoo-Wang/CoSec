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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpAttributes
import me.ahoo.cosec.runtime.snapshot.MissingPlatformException
import me.ahoo.cosec.runtime.snapshot.ViewDocuments
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.atomic.AtomicLong

/**
 * The control plane's decisions (RFC 0007 §10.3, Q22, CTL-3): the enforcing flow's parts, not a second evaluator. The
 * scope is resolved by [ScopeResolver], the documents are compiled by the data plane's [PolicyDocumentCompiler] with
 * the platform's limits and attributes and the host's [attributes], into the view enforcement would read, and [engine]
 * is the data plane's `Engine.evaluate`, at full trace. A stored document is compiled once for its content, in a cache
 * `simulate`, `explain` and `analyze` share (RC1-41).
 */
@InternalCoSecApi
class Simulator(
    private val engine: Engine = CoSecEngine,
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

    fun evaluate(request: SimulatedRequest, scope: Scope, documents: Documents): Evaluation =
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
            EngineRequest(authz, scope, memberships, request.time, http),
            view,
            TraceLevel.FULL
        )
        return Decided(evaluation, scope, view.version!!)
    }

    /** An evaluation, and the documents its view read with their revisions. */
    internal class Decided(val evaluation: Evaluation, scope: Scope, version: ViewVersion) {
        /** The revisions the view read: an app's only when it took the app's document (its revision, 0 for none). */
        val version = ViewVersion(version.platform, version.app, version.tenant ?: 0)

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

    @InternalCoSecApi
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
        is Cause.DocumentInvalid -> listOf(cause::class, cause.document, cause.revision, failure(cause.error))
        is Cause.BoundaryUnavailable -> listOf(cause::class, cause.boundary, failure(cause.error))
        is Cause.MembershipUnavailable -> listOf(cause::class, failure(cause.error))
        else -> cause
    }
    else -> decision
}

private fun failure(error: Throwable): Pair<Class<*>, String?> = error.javaClass to error.message
