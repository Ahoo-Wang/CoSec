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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CompileError
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolver

/**
 * What a document's `roles` and short-form permissions mean (RFC 0012 §6), and the only source of it: stored documents
 * stay in short form, and everything that reads what a document grants reads [effective]. A role must not grant
 * through a path the grant analysis, the reference index or the diff did not see, so none of them reads `roles` or a
 * catalog's `action` as anything but the input of this function.
 *
 * A catalog entry with `action` is the policy `perm:<app>/<id>`, one ALLOW; one in a group that is not `spaced` carries
 * `{ "eq": ["scope.space", ""] }`. A role is one binding per permission, `role:<roleId>@<app>:<permission>`, after the
 * hand-written bindings. A role id has no `@` and an app id no `:`, so the concatenation cannot make two ids equal.
 *
 * [effective] is total on any document that decodes, and decides nothing: it derives what a document says, broken or
 * not, and [check], which only the compiler runs, says what is wrong with it.
 */
object RoleExpansion {
    /** RFC 0012 §6.3: a short form multiplies, so a document may derive at most this many bindings. */
    const val DEFAULT_ROLE_GRANT_LIMIT: Int = 50_000

    /** The binding ids this expansion owns, reserved to the control plane's operations. */
    const val BINDING_PREFIX: String = "role:"

    private val DEFAULT_SPACE_ONLY = Condition.Eq(Attribute.Kind.SCOPE_SPACE.path, Literal.Str(""))

    /** [document] with its roles and short forms written out: a document with neither, unchanged. */
    fun effective(document: PolicyDocument): PolicyDocument = expand(document, DEFAULT_ROLE_GRANT_LIMIT).document

    /** How many bindings [document]'s roles derive, without deriving them. */
    @InternalCoSecApi
    fun grants(document: PolicyDocument): Long {
        val all = definedInCatalog(document).size.toLong()
        return document.roles.sumOf { role ->
            when (val permissions = role.permissions) {
                RolePermissions.All -> all
                is RolePermissions.Listed -> permissions.ids.size.toLong()
            }
        }
    }

    /**
     * The bindings [role] derives in a document of [kind] and [owner] whose catalog-defined permissions [catalog] gives,
     * in order: what `cosec:grant` is checked for (RFC 0012 §7.2). Empty when [role] cannot be in such a document.
     */
    @InternalCoSecApi
    fun bindings(kind: DocumentKind, owner: String, role: RoleDefinition, catalog: Set<String>): List<Binding> {
        if (role.shapeProblem(kind) != null) {
            return emptyList()
        }
        return permissionsOf(role, catalog).map { binding(kind, owner, role, it) }
    }

    /** The permissions of [document]'s catalog a role with `"*"` gets: defined here, by `action` or by a policy. */
    @InternalCoSecApi
    fun definedInCatalog(document: PolicyDocument?): Set<String> {
        val catalog = document?.catalog ?: return emptySet()
        val written = document.policies.mapTo(HashSet()) { it.id.value }
        val prefix = PolicyDocumentCompiler.permissionPrefix(document.owner)
        val defined = LinkedHashSet<String>()
        for (group in catalog.groups) {
            group.permissions.filter { it.actions != null || prefix + it.id in written }.mapTo(defined) { it.id }
        }
        return defined
    }

    /** [effective] with where each derived entry was written. Over [limit] it derives nothing and throws. */
    @InternalCoSecApi
    fun expand(document: PolicyDocument, limit: Int = DEFAULT_ROLE_GRANT_LIMIT): Expansion {
        val shortForm = hasShortForm(document)
        if (document.roles.isEmpty() && !shortForm) {
            return Expansion(document, document.policies.size, emptyList(), document.bindings.size, emptyList())
        }
        val grants = grants(document)
        if (grants > limit) {
            throw DocumentCompileException(
                document.describe(),
                listOf(CompileError("$.roles", "the roles derive $grants bindings, more than $limit")),
            )
        }
        val policies = ArrayList(document.policies)
        val policySources = derivePolicies(document, policies)
        val bindings = ArrayList(document.bindings)
        val bindingSources = deriveBindings(document, bindings)
        val effective = PolicyDocument(
            kind = document.kind,
            owner = document.owner,
            revision = document.revision,
            publishedAt = document.publishedAt,
            policies = policies,
            bindings = bindings,
            catalog = if (shortForm) document.catalog?.let(::displayOnly) else document.catalog,
            boundary = document.boundary,
            provenance = document.provenance,
            limits = document.limits,
            attributes = document.attributes,
            rateLimits = document.rateLimits,
        )
        return Expansion(effective, document.policies.size, policySources, document.bindings.size, bindingSources)
    }

    /**
     * What keeps [document]'s roles and short forms from compiling (RFC 0012 §4.3, §5.3, §6.2), each at the JSON path it
     * is written at. Only what `roles` or `action` introduce, so no document published before them stops loading.
     */
    @InternalCoSecApi
    fun check(document: PolicyDocument): List<CompileError> {
        val errors = ArrayList<CompileError>()
        checkCatalog(document, errors)
        if (document.roles.isNotEmpty()) {
            checkRoles(document, errors)
        }
        return errors
    }

    private fun hasShortForm(document: PolicyDocument): Boolean =
        document.catalog?.groups?.any { group -> group.permissions.any { it.actions != null } } == true

    private fun derivePolicies(document: PolicyDocument, into: MutableList<PolicyDefinition>): List<String> {
        val sources = ArrayList<String>()
        document.catalog?.groups?.forEachIndexed { g, group ->
            group.permissions.forEachIndexed { p, permission ->
                permission.actions?.let { actions ->
                    into += permissionPolicy(document.owner, group, permission, actions)
                    sources += "$.catalog.groups[$g].permissions[$p]"
                }
            }
        }
        return sources
    }

    private fun permissionPolicy(
        owner: String,
        group: CatalogGroup,
        permission: CatalogPermission,
        actions: List<String>,
    ) = PolicyDefinition(
        id = PolicyId(PolicyDocumentCompiler.permissionPrefix(owner) + permission.id),
        attachable = false,
        condition = if (group.spaced) null else DEFAULT_SPACE_ONLY,
        statements = listOf(StatementSource(permission.id, Effect.ALLOW, actions, permission.resources)),
    )

    private fun deriveBindings(document: PolicyDocument, into: MutableList<Binding>): List<String> {
        val sources = ArrayList<String>()
        val catalog = definedInCatalog(document)
        document.roles.forEachIndexed { r, role ->
            permissionsOf(role, catalog).forEachIndexed { p, permission ->
                into += binding(document.kind, document.owner, role, permission)
                sources += if (role.permissions == RolePermissions.All) "$.roles[$r].permissions" else "$.roles[$r].permissions[$p]"
            }
        }
        return sources
    }

    private fun permissionsOf(role: RoleDefinition, catalog: Set<String>): Collection<String> =
        when (val permissions = role.permissions) {
            RolePermissions.All -> catalog
            is RolePermissions.Listed -> permissions.ids
        }

    private fun binding(kind: DocumentKind, owner: String, role: RoleDefinition, permission: String): Binding {
        val app = if (kind == DocumentKind.APP) owner else requireNotNull(role.app) { "Checked by shapeProblem." }.value
        val tenant = if (kind == DocumentKind.TENANT) TenantId(owner) else null
        val space: Match<SpaceId> = role.space?.let { Match.Exactly(it) } ?: Match.Any
        return Binding(
            id = BindingId("$BINDING_PREFIX${role.id.value}@$app:$permission"),
            subject = SubjectRef.Role(tenant, role.id),
            scope = BindingScope(Match.Exactly(AppId(app)), space),
            policy = PolicyId(PolicyDocumentCompiler.permissionPrefix(app) + permission),
        )
    }

    /** The catalog as it reads once its short forms are written out as policies: expanding again adds nothing. */
    private fun displayOnly(catalog: Catalog) = Catalog(
        catalog.groups.map { group ->
            CatalogGroup(
                group.name,
                group.description,
                group.spaced,
                group.permissions.map { CatalogPermission(it.id, it.name, it.description) },
            )
        },
    )

    private fun checkCatalog(document: PolicyDocument, errors: MutableList<CompileError>) {
        val catalog = document.catalog ?: return
        val written = document.policies.mapTo(HashSet()) { it.id.value }
        val prefix = PolicyDocumentCompiler.permissionPrefix(document.owner)
        val entries = catalog.groups.flatMap { it.permissions }.groupingBy { it.id }.eachCount()
        catalog.groups.forEachIndexed { g, group ->
            group.permissions.forEachIndexed { p, permission ->
                val path = "$.catalog.groups[$g].permissions[$p]"
                if (permission.actions == null) {
                    if (permission.resources.isNotEmpty()) {
                        errors += CompileError("$path.resource", "a resource needs an action")
                    }
                    return@forEachIndexed
                }
                when {
                    permission.id.isEmpty() -> errors += CompileError("$path.id", "a permission with an action has an id")
                    prefix + permission.id in written -> errors += CompileError(
                        "$path.action",
                        "[${permission.id}] has an action and a hand-written policy; a permission has one definition",
                    )
                    entries.getValue(permission.id) > 1 ->
                        errors += CompileError("$path.id", "[${permission.id}] is listed twice and defined by an action")
                }
            }
        }
    }

    private fun checkRoles(document: PolicyDocument, errors: MutableList<CompileError>) {
        val roles = HashSet<RoleId>()
        val bindings = document.bindings.mapTo(HashSet()) { it.id.value }
        val defined = if (document.kind == DocumentKind.APP) defined(document) else null
        val catalog = definedInCatalog(document)
        document.roles.forEachIndexed { r, role ->
            val path = "$.roles[$r]"
            idProblem(role.id.value)?.let { errors += CompileError("$path.id", it) }
            if (!roles.add(role.id)) {
                errors += CompileError("$path.id", "role [${role.id.value}] is defined twice in this document")
            }
            role.app?.value?.takeUnless(ScopeResolver::isValidAppId)?.let {
                errors += CompileError("$path.app", "[$it] cannot be an app id")
            }
            role.space?.value?.takeIf(ScopeResolver::isReservedSpaceId)?.let {
                errors += CompileError(
                    "$path.space",
                    "[$it] is reserved: space ids beginning with [${ScopeResolver.RESERVED_SPACE_PREFIX}] are CoSec's",
                )
            }
            checkPermissions(document, role, path, defined, errors)
            checkCollisions(document, role, catalog, bindings, path, errors)
        }
    }

    private fun checkPermissions(
        document: PolicyDocument,
        role: RoleDefinition,
        path: String,
        defined: Set<String>?,
        errors: MutableList<CompileError>,
    ) {
        val listed = (role.permissions as? RolePermissions.Listed)?.ids ?: return
        val seen = HashSet<String>()
        listed.forEachIndexed { p, permission ->
            val at = "$path.permissions[$p]"
            when {
                permission.isEmpty() || permission == Match.WILDCARD ->
                    errors += CompileError(at, "[$permission] is not a permission id")
                !seen.add(permission) -> errors += CompileError(at, "[$permission] is listed twice")
                defined != null && permission !in defined ->
                    errors += CompileError(at, "[$permission] is not a permission app ${document.owner} defines")
            }
        }
    }

    private fun checkCollisions(
        document: PolicyDocument,
        role: RoleDefinition,
        catalog: Set<String>,
        written: Set<String>,
        path: String,
        errors: MutableList<CompileError>,
    ) {
        for (binding in bindings(document.kind, document.owner, role, catalog)) {
            if (binding.id.value in written) {
                errors += CompileError("$path.permissions", "binding [${binding.id.value}] is also written by hand")
            }
        }
    }

    /** The permissions an app document defines: its policies `perm:<app>/...`, and its catalog entries with `action`. */
    private fun defined(document: PolicyDocument): Set<String> {
        val prefix = PolicyDocumentCompiler.permissionPrefix(document.owner)
        val defined = document.policies.filter { it.id.value.startsWith(prefix) }
            .mapTo(HashSet()) { it.id.value.removePrefix(prefix) }
        document.catalog?.groups?.forEach { group ->
            group.permissions.filter { it.actions != null }.mapTo(defined) { it.id }
        }
        return defined
    }

    private fun idProblem(id: String): String? = when {
        id.isEmpty() -> "a role id must not be empty"
        id == Match.WILDCARD -> "a role id must not be [${Match.WILDCARD}]"
        '@' in id -> "[$id]: a role id has no [@], which separates a membership's role and space"
        else -> null
    }
}

/** [document] and where each entry it derived was written; the compiler and the checks report errors there. */
@InternalCoSecApi
class Expansion internal constructor(
    val document: PolicyDocument,
    private val ownPolicies: Int,
    private val policySources: List<String>,
    private val ownBindings: Int,
    private val bindingSources: List<String>,
) {
    /** Whether the policy at [index] of [document] is derived from a catalog entry. */
    fun derivedPolicy(index: Int): Boolean = index >= ownPolicies

    /** The JSON path the policy at [index] of [document] was written at. */
    fun policyPath(index: Int): String =
        if (derivedPolicy(index)) policySources[index - ownPolicies] else "$.policies[$index]"

    /** The JSON path the binding at [index] of [document] was written at. */
    fun bindingPath(index: Int): String =
        if (index >= ownBindings) bindingSources[index - ownBindings] else "$.bindings[$index]"
}

internal fun PolicyDocument.describe(): String = when (kind) {
    DocumentKind.PLATFORM -> "platform"
    DocumentKind.APP -> "app $owner"
    DocumentKind.TENANT -> "tenant $owner"
}
