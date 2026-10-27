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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.CoSecException
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.CompileError
import me.ahoo.cosec.engine.flat.CompileException
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.CompiledPolicy
import me.ahoo.cosec.engine.flat.PolicyCompiler
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotCompiler
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey

/** A document that decodes but does not compile; it is never installed, and every request needing it fails closed. */
class DocumentCompileException(val document: String, val errors: List<CompileError>, cause: Throwable? = null) :
    CoSecException(
        "The $document document does not compile: " + errors.joinToString("; ") { "${it.path}: ${it.message}" },
        cause,
    )

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
class PolicyDocumentCompiler(
    private val policies: PolicyCompiler = PolicyCompiler(ConditionChecker(zones = JavaZones.RESOLVER)),
    val principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
) : SnapshotCompiler<CompiledDocument> {
    /**
     * Compiles conditions against [catalog] too: the external attributes the registered `AttributeProvider`s declare
     * (RFC 0006 §5.4.1). A statement reading an `ext.` path no provider declares does not compile.
     */
    constructor(catalog: AttributeCatalog, principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT) : this(
        PolicyCompiler(ConditionChecker(zones = JavaZones.RESOLVER, catalog = catalog)),
        principalBindingLimit,
    )

    init {
        require(principalBindingLimit >= 0) { "The principal binding limit must not be negative." }
    }

    /**
     * Decodes and compiles a stored document. [SnapshotKey.Policies] is RFC 0003's 7.0-shaped document, which this
     * compiler does not read.
     */
    override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
        val (kind, owner) = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
            SnapshotKey.Policies -> throw SnapshotFormatException("[$key] is not a platform, app or tenant document.")
        }
        if (document.schema != PolicyDocumentCodec.SCHEMA) {
            throw SnapshotFormatException(
                "[$key] has schema ${document.schema}, not this version's document format ${PolicyDocumentCodec.SCHEMA}.",
            )
        }
        return compile(PolicyDocumentCodec.decode(kind, owner, document.bodyBytes()))
    }

    fun compile(document: PolicyDocument): CompiledDocument {
        val name = describe(document)
        val errors = ArrayList<CompileError>()
        val compiled = document.policies.mapIndexedNotNull { i, policy ->
            policy(
                document,
                policy,
                "$.policies[$i]",
                errors
            )
        }
        checkBindings(document, errors)
        checkBoundaries(document, errors)
        checkAttributes(document, errors)
        if (errors.isNotEmpty()) {
            throw DocumentCompileException(name, errors)
        }
        val boundaryIds = document.policies.filter { it.boundary }.mapTo(HashSet()) { it.id.value }
        val (boundaries, policies) = compiled.partition { it.policyId.value in boundaryIds }
        return try {
            when (document.kind) {
                DocumentKind.PLATFORM ->
                    CompiledDocument.platform(policies, document.bindings, boundaries, document.boundary?.value)

                DocumentKind.APP -> CompiledDocument.app(AppId(document.owner), compiled, document.bindings)
                DocumentKind.TENANT ->
                    CompiledDocument.tenant(
                        TenantId(document.owner),
                        compiled,
                        document.bindings,
                        document.boundary?.value
                    )
            }
        } catch (error: IllegalArgumentException) {
            throw DocumentCompileException(name, listOf(CompileError("$", error.message ?: "invalid")), error)
        }
    }

    /**
     * Fails with [DocumentCompileException] when an app or tenant [document] binds a policy that neither it nor
     * [platform] defines. The data plane does not need this (a missing policy fails the request closed); a publisher
     * checks it before writing, so that a typo is an error rather than an outage.
     */
    fun checkReferences(document: PolicyDocument, platform: PolicyDocument) {
        if (document.kind == DocumentKind.PLATFORM) {
            return
        }
        val defined = document.policies.mapTo(HashSet()) { it.id.value } + platform.policies.map { it.id.value } +
            BuiltinPolicies.ROOT.value
        val errors = document.bindings.withIndex().filter { (_, binding) -> binding.policy.value !in defined }
            .map { (i, binding) ->
                CompileError(
                    "$.bindings[$i].policy",
                    "[${binding.policy.value}] is defined neither here nor in the platform"
                )
            }
        if (errors.isNotEmpty()) {
            throw DocumentCompileException(describe(document), errors)
        }
    }

    private fun policy(
        document: PolicyDocument,
        policy: PolicyDefinition,
        path: String,
        errors: MutableList<CompileError>,
    ): CompiledPolicy? {
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
            // Only the platform document may let a DENY lapse while its provider fails (RFC 0006 Q13).
            policies.compile(
                policy.id,
                policy.condition,
                policy.statements,
                policy.attachable,
                denyNomatch = document.kind == DocumentKind.PLATFORM,
            )
        } catch (error: CompileException) {
            error.errors.forEach { errors += CompileError(path + it.path.removePrefix("$"), it.message) }
            null
        }
    }

    private fun checkBindings(document: PolicyDocument, errors: MutableList<CompileError>) {
        var principals = 0
        for ((i, binding) in document.bindings.withIndex()) {
            if (binding.subject is SubjectRef.Principal) {
                principals++
            }
            val id = binding.policy.value
            if (!id.startsWith(PERMISSION_PREFIX)) {
                continue
            }
            val ownsIt = document.kind == DocumentKind.APP && id.startsWith(permissionPrefix(document.owner))
            if (!ownsIt) {
                errors += CompileError(
                    "$.bindings[$i].policy",
                    "[$id] is another app's permission policy; only that app's document binds it",
                )
            } else if (document.policies.none { it.id.value == id }) {
                errors += CompileError("$.bindings[$i].policy", "[$id] is not defined in this document")
            }
        }
        if (principals > principalBindingLimit) {
            errors += CompileError(
                "$.bindings",
                "$principals principal bindings, more than $principalBindingLimit: grant users through tokens or a " +
                    "membership provider",
            )
        }
    }

    private fun checkBoundaries(document: PolicyDocument, errors: MutableList<CompileError>) {
        val boundaries = HashSet<String>()
        for ((i, policy) in document.policies.withIndex()) {
            if (policy.boundary) {
                boundaries += policy.id.value
                checkBoundaryPolicy(document.kind, "$.policies[$i]", policy, errors)
            }
        }
        for ((i, binding) in document.bindings.withIndex()) {
            if (binding.policy.value in boundaries) {
                errors += CompileError("$.bindings[$i].policy", "[${binding.policy.value}] is a boundary; boundaries are not bound")
            }
        }
        val boundary = document.boundary ?: return
        when {
            document.kind == DocumentKind.PLATFORM && boundary.value !in boundaries ->
                errors += CompileError("$.boundaries.default", "[${boundary.value}] is not a boundary of this document")

            document.kind == DocumentKind.TENANT && document.owner == TenantId.PLATFORM.value ->
                errors += CompileError("$.boundary", "the platform tenant has no boundary")
        }
    }

    /** A boundary policy (RFC 0007 §7.6): in the platform, never attached, only ALLOWs, at most 64 of them. */
    private fun checkBoundaryPolicy(
        kind: DocumentKind,
        path: String,
        policy: PolicyDefinition,
        errors: MutableList<CompileError>
    ) {
        if (kind != DocumentKind.PLATFORM) {
            errors += CompileError("$path.boundary", "only the platform document holds boundaries")
        }
        if (policy.attachable) {
            errors += CompileError("$path.attachable", "a boundary cannot be attached")
        }
        policy.statements.withIndex().filter { it.value.effect == Effect.DENY }.forEach { (s, _) ->
            errors += CompileError("$path.statements[$s].effect", "a boundary only allows")
        }
        if (policy.statements.size > CompiledDocument.MAX_BOUNDARY_STATEMENTS) {
            errors += CompileError(
                "$path.statements",
                "${policy.statements.size} statements, more than ${CompiledDocument.MAX_BOUNDARY_STATEMENTS}",
            )
        }
    }

    /** Each external attribute is declared once (RFC 0007 §3.1 N3). */
    private fun checkAttributes(document: PolicyDocument, errors: MutableList<CompileError>) {
        val paths = HashSet<String>()
        document.attributes?.forEachIndexed { i, attribute ->
            if (!paths.add(attribute.path)) {
                errors += CompileError("$.attributes[$i].path", "[${attribute.path}] is declared twice")
            }
        }
    }

    private fun describe(document: PolicyDocument): String = when (document.kind) {
        DocumentKind.PLATFORM -> "platform"
        DocumentKind.APP -> "app ${document.owner}"
        DocumentKind.TENANT -> "tenant ${document.owner}"
    }

    companion object {
        /**
         * The compiler every document compiles with when [platform] is the platform document (RFC 0007 §3.1 N3): its
         * condition limits and its declared external attributes, or the defaults where it declares none. The control
         * plane compiles candidates with it, so what it accepts is what an instance reading the same platform accepts.
         */
        fun forPlatform(
            platform: PolicyDocument?,
            principalBindingLimit: Int = DEFAULT_PRINCIPAL_BINDING_LIMIT,
        ): PolicyDocumentCompiler {
            val limits = platform?.limits ?: ConditionLimits.DEFAULT
            // A platform that declares a path twice does not compile; until it does, the first declaration counts.
            val catalog = platform?.attributes?.let { AttributeCatalog.of(it.distinctBy { attribute -> attribute.path }) }
                ?: AttributeCatalog.NONE
            return PolicyDocumentCompiler(
                PolicyCompiler(ConditionChecker(limits = limits, zones = JavaZones.RESOLVER, catalog = catalog)),
                principalBindingLimit,
            )
        }

        /** RFC 0004 Q2. */
        const val DEFAULT_PRINCIPAL_BINDING_LIMIT: Int = 1_000

        /** Permission policies are named `perm:<app>/<permission>` (RFC 0004 §5.1). */
        const val PERMISSION_PREFIX: String = "perm:"

        fun permissionPrefix(app: String): String = "$PERMISSION_PREFIX$app/"
    }
}
