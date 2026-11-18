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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompileError
import me.ahoo.cosec.engine.CompileException
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.CompiledPolicy
import me.ahoo.cosec.engine.CompiledRateLimit
import me.ahoo.cosec.engine.PolicyCompiler
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Compiles platform, app and tenant documents ([PolicyDocument]) into the engine's [CompiledDocument]s (RFC 0004 §7,
 * RFC 0005 §6). Every statement goes through [PolicyCompiler]; there is no other way into the engine.
 *
 * A document compiles whole or not at all: any error throws [DocumentCompileException] (or [SnapshotFormatException]
 * for a document that does not decode), the snapshot source keeps it as failed, and every request that needs the
 * document fails closed (`DOC-1`, RFC 0003 F4/F5). Errors carry the JSON path. Checked here, besides each policy:
 *
 * - Permission policies (`perm:<app>/...`) live in their app's document, are not attachable, and only that document
 *   binds them. Platform and tenant documents define none and bind none (`TNT-2`): a tenant cannot reach another app's
 *   or tenant's permissions, and an app document's change cannot leave a tenant reference dangling.
 * - A document binds at most [principalBindingLimit] principals (RFC 0004 §4.4, Q2): user-level grants belong in
 *   tokens or a membership provider, not in a snapshot.
 * - Roles and short-form permissions (RFC 0012): what compiles is [RoleExpansion.effective] of the document, which
 *   derives at most [roleGrantLimit] bindings; [RoleExpansion.check] says what is wrong with what it was derived from.
 * - What [CompiledDocument] checks: unique ids, which subjects each document binds, reachable binding shapes, the
 *   break-glass rules and the built-in policies (`BND-3`, `BND-8`, `BRK-2`, `BRK-4`).
 * - Platform boundaries (RFC 0007 §7.6, Q11): boundary policies only in the platform document, only ALLOW statements, at
 *   most [CompiledDocument.MAX_BOUNDARY_STATEMENTS], neither attachable nor bound; the platform's default names one of
 *   them; the platform tenant names no boundary. Whether a tenant's boundary exists is looked up per request, like any
 *   reference to the platform: a missing one fails the request closed.
 *
 * References from app and tenant documents to platform policies are looked up per request ([CompiledDocument]): the
 * platform may change on its own, and a missing one makes the request indeterminate. [checkReferences] checks them
 * against a given platform document, as a publisher does before it writes (RFC 0004 §7.4).
 */
class PolicyDocumentCompiler @InternalCoSecApi constructor(
    private val policies: PolicyCompiler = PolicyCompiler(ConditionChecker(zones = JavaZones.RESOLVER)),
    @property:InternalCoSecApi
    val principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
    @property:InternalCoSecApi
    val roleGrantLimit: Int = RoleExpansion.DEFAULT_ROLE_GRANT_LIMIT,
) : SnapshotCompiler<CompiledDocument> {
    /**
     * Compiles conditions against [catalog] too: the external attributes the registered `AttributeProvider`s declare
     * (RFC 0006 §5.4.1). A statement reading an `ext.` path no provider declares does not compile.
     */
    @InternalCoSecApi
    constructor(
        catalog: AttributeCatalog,
        principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
    ) : this(
        PolicyCompiler(ConditionChecker(zones = JavaZones.RESOLVER, catalog = catalog)),
        principalBindingLimit,
    )

    private val checks = DocumentChecks(principalBindingLimit, roleGrantLimit)

    init {
        require(principalBindingLimit >= 0) { "The principal binding limit must not be negative." }
        require(roleGrantLimit >= 0) { "The role grant limit must not be negative." }
    }

    /** Decodes and compiles a stored document. */
    @InternalCoSecApi
    override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
        val (kind, owner) = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
        }
        if (document.schema != PolicyDocumentCodec.SCHEMA) {
            throw SnapshotFormatException(
                "[$key] has schema ${document.schema}, not this version's document format ${PolicyDocumentCodec.SCHEMA}.",
            )
        }
        return compile(PolicyDocumentCodec.decode(kind, owner, document.bodyBytes()))
    }

    /**
     * Throws [DocumentCompileException] when [document] does not compile, with every error and its JSON path. What a CI
     * check of a document calls: the compiled form is the engine's, and no application needs it.
     */
    fun validate(document: PolicyDocument) {
        compile(document)
    }

    @InternalCoSecApi
    fun compile(document: PolicyDocument): CompiledDocument {
        val name = document.describe()
        val expansion = RoleExpansion.expand(document, roleGrantLimit)
        val effective = expansion.document
        val errors = ArrayList(RoleExpansion.check(document))
        val compiled = effective.policies.mapIndexedNotNull { i, policy ->
            policy(
                effective,
                policy,
                expansion,
                i,
                errors
            )
        }
        val rateLimits = document.rateLimits.mapIndexedNotNull { i, rule ->
            rateLimit(
                document,
                rule,
                "$.rateLimits[$i]",
                errors
            )
        }
        checks.checkBindings(document, effective, errors)
        checks.checkBoundaries(document, errors)
        checks.checkAttributes(document, errors)
        if (errors.isNotEmpty()) {
            throw DocumentCompileException(name, errors)
        }
        return try {
            assemble(document, effective, compiled, rateLimits)
        } catch (error: IllegalArgumentException) {
            throw DocumentCompileException(name, listOf(CompileError("$", error.message ?: "invalid")), error)
        }
    }

    /** The engine's document of [document], from what compiled. */
    private fun assemble(
        document: PolicyDocument,
        effective: PolicyDocument,
        compiled: List<CompiledPolicy>,
        rateLimits: List<CompiledRateLimit>,
    ): CompiledDocument {
        val boundaryIds = document.policies.filter { it.boundary }.mapTo(HashSet()) { it.id.value }
        val (boundaries, policies) = compiled.partition { it.policyId.value in boundaryIds }
        return when (document.kind) {
            DocumentKind.PLATFORM ->
                CompiledDocument.platform(
                    policies,
                    effective.bindings,
                    rateLimits,
                    boundaries,
                    document.boundary?.value
                )

            DocumentKind.APP -> CompiledDocument.app(AppId(document.owner), compiled, effective.bindings, rateLimits)
            DocumentKind.TENANT ->
                CompiledDocument.tenant(
                    TenantId(document.owner),
                    compiled,
                    effective.bindings,
                    rateLimits,
                    document.boundary?.value,
                    boundApps(document),
                )
        }
    }

    /**
     * Fails with [DocumentCompileException] when an app or tenant [document] binds a policy that neither it nor
     * [platform] defines. The data plane does not need this (a missing policy fails the request closed); a publisher
     * checks it before writing, so that a typo is an error rather than an outage.
     */
    fun checkReferences(document: PolicyDocument, platform: PolicyDocument) {
        checks.checkReferences(document, platform)
    }

    private fun policy(
        document: PolicyDocument,
        policy: PolicyDefinition,
        expansion: Expansion,
        index: Int,
        errors: MutableList<CompileError>,
    ): CompiledPolicy? {
        val path = expansion.policyPath(index)
        val id = policy.id.value
        val permission = id.startsWith(PERMISSION_PREFIX)
        when (document.kind) {
            DocumentKind.APP -> {
                if (!id.startsWith(permissionPrefix(document.owner))) {
                    errors += CompileError("$path.id", "a policy of app ${document.owner} is [${permissionPrefix(document.owner)}<permission>], not [$id]")
                }
                if (policy.attachable) {
                    errors += CompileError("$path.attachable", "a permission policy cannot be attached")
                }
            }

            else -> if (permission) {
                errors += CompileError("$path.id", "[$PERMISSION_PREFIX] names an app's permission policy, defined only in that app's document")
            }
        }
        return try {
            // A DENY may lapse while its provider fails only with the control plane's confirmation (RFC 0007 §9.5, N6).
            policies.compile(policy.id, policy.condition, policy.statements, policy.attachable)
        } catch (error: CompileException) {
            // A derived policy's one statement is what its catalog entry writes: the entry is where it is wrong.
            val written = if (expansion.derivedPolicy(index)) ".statements[0]" else ""
            error.errors.forEach { errors += CompileError(path + it.path.removePrefix("$").removePrefix(written), it.message) }
            null
        }
    }

    /** A rate limit rule (RFC 0006 §8.1); its errors are reported at [path]. */
    private fun rateLimit(
        document: PolicyDocument,
        rule: RateLimitSource,
        path: String,
        errors: MutableList<CompileError>,
    ): CompiledRateLimit? = try {
        policies.rateLimit(document.kind, document.owner, rule)
    } catch (error: CompileException) {
        error.errors.forEach { errors += CompileError(path + it.path.removePrefix("$"), it.message) }
        null
    }

    companion object {
        /**
         * The apps whose permission policies a tenant [document] binds to `Anyone` or to a principal: a request in one of
         * them reads that app's document whatever its roles, or the binding could never resolve (RC1-17). The one
         * computation of it: the compiled tenant document answers `CompiledDocument.needsApp` from it, and the control
         * plane reads it from a document it has not compiled (`ViewDocuments`). `DocumentChecks` has checked that each
         * such binding is scoped to exactly the policy's app.
         */
        @InternalCoSecApi
        fun boundApps(document: PolicyDocument): Set<String> = document.bindings
            .filter { it.subject !is SubjectRef.Role && it.policy.value.startsWith(PERMISSION_PREFIX) }
            .mapNotNullTo(HashSet()) { (it.scope.app as? Match.Exactly)?.value?.value }

        /** The compiler a document is validated with when [platform] is the platform document, as the control plane checks a candidate. */
        fun forPlatform(
            platform: PolicyDocument?
        ): PolicyDocumentCompiler = forPlatform(platform, AttributeCatalog.NONE)

        /**
         * The compiler every document compiles with when [platform] is the platform document (RFC 0007 §3.1 N3): its
         * condition limits, or the defaults, and the external attributes it declares ahead of those the host's providers
         * declare ([providers]). The data plane, `simulate` and the control plane's checks all compile with it, so what
         * one accepts the others accept. [credentialHeaders] are the headers the host reads credentials from besides
         * the fixed ones, such as a renamed API key header (`CND-5`, RC1-21).
         */
        @InternalCoSecApi
        fun forPlatform(
            platform: PolicyDocument?,
            providers: AttributeCatalog = AttributeCatalog.NONE,
            principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
            credentialHeaders: Set<String> = emptySet(),
            roleGrantLimit: Int = RoleExpansion.DEFAULT_ROLE_GRANT_LIMIT,
        ): PolicyDocumentCompiler {
            val limits = platform?.limits ?: ConditionLimits.DEFAULT
            // A platform that declares a path twice does not compile; until it does, the first declaration counts.
            val declared = platform?.attributes.orEmpty().distinctBy { it.path }.associateBy { it.path }
            val catalog = AttributeCatalog { path -> declared[path] ?: providers.external(path) }
            return of(limits, catalog, principalBindingLimit, credentialHeaders, roleGrantLimit)
        }

        /**
         * A compiler that checks conditions against [limits] and the external attributes of [catalog], refusing
         * [credentialHeaders] as it refuses the fixed credential headers.
         */
        @InternalCoSecApi
        fun of(
            limits: ConditionLimits,
            catalog: AttributeCatalog,
            principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
            credentialHeaders: Set<String> = emptySet(),
            roleGrantLimit: Int = RoleExpansion.DEFAULT_ROLE_GRANT_LIMIT,
        ): PolicyDocumentCompiler = PolicyDocumentCompiler(
            PolicyCompiler(
                ConditionChecker(
                    limits = limits,
                    zones = JavaZones.RESOLVER,
                    catalog = catalog,
                    credentialHeaders = credentialHeaders,
                )
            ),
            principalBindingLimit,
            roleGrantLimit,
        )

        /** RFC 0004 Q2. */
        @InternalCoSecApi
        const val DEFAULT_PRINCIPAL_BINDING_LIMIT: Int = 1_000

        /** Permission policies are named `perm:<app>/<permission>` (RFC 0004 §5.1). */
        @InternalCoSecApi
        const val PERMISSION_PREFIX: String = "perm:"

        @InternalCoSecApi
        fun permissionPrefix(app: String): String = "$PERMISSION_PREFIX$app/"
    }
}
