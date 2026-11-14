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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.migrate.v7.api.permission.AppPermission
import me.ahoo.cosec.migrate.v7.api.permission.AppRolePermission
import me.ahoo.cosec.migrate.v7.api.policy.Policy
import me.ahoo.cosec.migrate.v7.api.policy.PolicyType
import me.ahoo.cosec.migrate.v7.api.principal.SpacedRoleId
import me.ahoo.cosec.migrate.v7.serialization.V7JsonSerializer
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.CatalogGroup
import me.ahoo.cosec.runtime.document.CatalogPermission
import me.ahoo.cosec.runtime.document.DocumentCompileException
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** Where non-global policies go (RFC 0004 §5.2). */
enum class Partition {
    /** Every policy in the platform document, attachable by anyone: 7.x semantics (option A, the default). */
    PLATFORM,

    /** Each non-global policy in the document of the tenant its `tenantId` names (option B; C5). */
    BY_OWNER,
}

/**
 * How to convert. There is no root: a 7.x root principal is not converted, since cosec-migrate writes no break-glass
 * binding; after the switch, break-glass is requested through the control plane, by two people (RFC 0007 §8, RC1-27).
 */
class ConvertOptions(
    val partition: Partition = Partition.PLATFORM,
    val publishedAt: Instant = Instant.now(),
)

/**
 * The result of [Converter.convert]: the documents at revision 1 and where each of their parts came from, or no
 * documents when the report holds a rejection.
 */
class Conversion(
    val documents: Map<SnapshotKey, PolicyDocument>,
    val provenance: Provenance,
    val report: ConversionReport,
    /** The translated rate limit rules; none when the report holds a rejection. */
    val rateLimits: List<ConvertedRateLimit> = emptyList(),
)

/**
 * Where each 8.0 policy statement and binding came from in 7.x (RFC 0004 §8.2), so a decision can be traced to the
 * 7.x data an operator knows.
 */
class Provenance(val policies: List<PolicyOrigin>, val bindings: List<BindingOrigin>) {
    /** Policy [policyId] in [document]; `statements[i]` is the 7.x location of its statement `i`. */
    class PolicyOrigin(val document: String, val policyId: String, val source: String, val statements: List<String>)

    class BindingOrigin(val document: String, val bindingId: String, val source: String)
}

/**
 * Converts 7.x policy data into 8.0 platform, app and tenant documents (RFC 0004 §5.1, §8.3), refusing what §8.4 and
 * RFC 0008 §4.1 refuse. Every document it returns compiles with [compiler]; a run with any rejection returns none.
 */
class Converter(
    /**
     * What every converted policy compiles with: the default limits, and the one external attribute a conversion may
     * write, `ext.ip2region.country` (RFC 0006 §5.4.5), which the platform document then declares ([IP2REGION_COUNTRY]).
     */
    private val compiler: PolicyDocumentCompiler = PolicyDocumentCompiler.forPlatform(
        null,
        AttributeCatalog.of(listOf(IP2REGION_COUNTRY)),
    ),
) {
    fun convert(source: Source7x, options: ConvertOptions = ConvertOptions()): Conversion =
        Run(source, options, compiler).convert()

    companion object {
        /** Prefixes of the ids the converter generates (RFC 0004 §8.3); a 7.x policy id must not start with one. */
        val RESERVED_PREFIXES = listOf(PolicyDocumentCompiler.PERMISSION_PREFIX, "g:", "r:", "cosec:")

        /** The binding of global policy [policyId]. */
        fun globalBindingId(policyId: String): String = "g:$policyId"

        /** The [n]th binding (from 0) of a role grant, in the order the grant lists its permissions. */
        fun grantBindingId(roleId: String, spaceId: String, n: Int): String =
            if (spaceId.isEmpty()) "r:$roleId:$n" else "r:$roleId${SpacedRoleId.SPACE_ID_SEPARATOR}$spaceId:$n"

        fun permissionPolicyId(appId: String, permissionId: String): String =
            PolicyDocumentCompiler.permissionPrefix(appId) + permissionId

        /** The declaration a converted platform document holds when a converted condition reads the client's country. */
        val IP2REGION_COUNTRY: Attribute = Attribute.external(Parts.IP2REGION_COUNTRY, AttributeType.STRING)
    }
}

private val POLICY_PATH = Regex("""^\$\.policies\[(\d+)]""")

/** Where the input of [path] in the document of [key] came from: its policy's origin, else the document's name. */
private fun originOf(
    origins: List<Provenance.PolicyOrigin>,
    key: SnapshotKey,
    policies: List<PolicyDefinition>,
    path: String,
): String {
    val index = POLICY_PATH.find(path)?.groupValues?.get(1)?.toInt()
    val policy = index?.let { policies.getOrNull(it) } ?: return key.toString()
    return origins.firstOrNull { it.document == key.toString() && it.policyId == policy.id.value }?.source ?: key.toString()
}

/**
 * The apps (or tenants) of a conversion by id, each of which names a file. An export may list an app twice, and which
 * entry is right is not for the migration to guess (#1232); two ids whose files differ only in case name one file on a
 * case-insensitive file system (macOS, Windows), where one document would replace the other. The names are the ones
 * [ConvertedDirectory] writes, not the raw ids: `straße` and `strasse` fold to one id in Unicode and to two files here
 * (#1259).
 */
private class SeenIds(
    private val kind: String,
    private val reject: (RejectionKind, String, String) -> Unit,
) {
    private val locations = HashMap<String, String>()
    private val folded = HashMap<String, String>()

    /** Whether [id] was seen at an earlier location, which is rejected. */
    fun repeated(id: String, location: String): Boolean {
        val first = locations.putIfAbsent(id, location) ?: return false
        reject(
            RejectionKind.DUPLICATE_ID,
            location,
            "$kind [${Identifiers.printable(id)}] is listed more than once (first at $first): one entry would replace " +
                "the other and lose its permissions or grants; keep one",
        )
        return true
    }

    /** Whether the file of [id] differs from the file of every other id in more than case. */
    fun distinctInCase(id: String, location: String): Boolean {
        val other = folded.putIfAbsent(ConvertedDirectory.fileName(id).lowercase(), id)
        if (other == null || other == id) {
            return true
        }
        reject(
            RejectionKind.DUPLICATE_ID,
            location,
            "$kind id [${Identifiers.printable(id)}] differs from [${Identifiers.printable(other)}] only in case: " +
                "their converted files would collide; rename one",
        )
        return false
    }
}

private class Run(val source: Source7x, val options: ConvertOptions, val compiler: PolicyDocumentCompiler) {
    val rejections = ArrayList<Rejection>()
    val warnings = ArrayList<Warning>()
    val changes = ArrayList<SemanticChange>()
    val counts = LinkedHashMap<String, Int>()
    val translator = MatcherTranslator { kind, location, message -> reject(kind, location, message) }
    val scan = SemanticScan { changes += it }
    val conditions = ConditionTranslator(
        { kind, location, message -> reject(kind, location, message) },
        { changes += it }
    )
    val statements = StatementTranslator(
        translator,
        conditions
    ) { kind, location, message -> reject(kind, location, message) }
    val treeReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    /** The policies and bindings of each document, in the order they are written. */
    val policies = LinkedHashMap<SnapshotKey, MutableList<PolicyDefinition>>()
    val bindings = LinkedHashMap<SnapshotKey, MutableList<Binding>>()
    val catalogs = HashMap<SnapshotKey, Catalog>()
    val policyOrigins = ArrayList<Provenance.PolicyOrigin>()
    val bindingOrigins = ArrayList<Provenance.BindingOrigin>()
    val rateLimits = ArrayList<ConvertedRateLimit>()

    /** The apps and tenants seen, for the files they name: file systems may ignore case. */
    val apps = SeenIds("app") { kind, location, message -> reject(kind, location, message) }
    val tenants = SeenIds("tenant") { kind, location, message -> reject(kind, location, message) }

    fun reject(kind: RejectionKind, location: String, message: String) {
        rejections += Rejection(kind, location, message)
    }

    fun count(name: String, by: Int = 1) {
        counts.merge(name, by, Int::plus)
    }

    fun convert(): Conversion {
        policies[SnapshotKey.Platform] = ArrayList()
        bindings[SnapshotKey.Platform] = ArrayList()
        // What PIP-18 changes for every converted deployment, whatever its statements say: reported once.
        changes += SemanticChange(
            "PIP-18",
            CONVERSION,
            "a HEAD request is decided as GET: it is permitted wherever GET is, and denied wherever GET is",
        )
        checkMarker()
        convertPolicies()
        source.apps.forEach(::convertApp)
        convertRateLimits()
        val built = if (rejections.isEmpty()) documents() else emptyMap()
        // RC1-52 (`IDN-10`): as the control plane refuses a request that holds one, nothing is published.
        built.forEach { (key, document) ->
            DocumentIds.unpaired(document).forEach { (path, id) ->
                reject(RejectionKind.INVALID_ID, "$key#$path", DocumentIds.unpairedMessage(id))
            }
        }
        val documents = if (rejections.isEmpty()) built else emptyMap()
        count("documents", documents.size)
        count("rejections", rejections.distinct().size)
        count("warnings", warnings.size)
        // A policy condition is translated once per direction and per permission policy: report each finding once.
        val report = ConversionReport(rejections.distinct(), warnings, changes.distinct(), counts, source.inputSha256)
        val limits = if (report.accepted) rateLimits.toList() else emptyList()
        return Conversion(documents, Provenance(policyOrigins, bindingOrigins), report, limits)
    }

    private fun checkMarker() {
        val marker = source.schemaMarker ?: return
        if (marker.value != SCHEMA_7) {
            reject(
                RejectionKind.SCHEMA_MARKER,
                marker.location,
                "the schema marker is [${marker.value ?: "absent"}], not [$SCHEMA_7]: this is not 7.0-layout data",
            )
        }
    }

    // ---- policies (RFC 0004 §5.1: GLOBAL, SYSTEM, CUSTOM) ----

    private fun convertPolicies() {
        val seen = HashSet<String>()
        for (entry in source.policies) {
            count("policies")
            if (!seen.add(entry.id)) {
                reject(RejectionKind.DUPLICATE_ID, entry.location, "policy [${entry.id}] is listed more than once")
                continue
            }
            convertPolicy(entry)
        }
        (source.globalIndex - seen).sorted().forEach { id ->
            reject(
                RejectionKind.GLOBAL_INDEX_WITHOUT_CONTENT,
                source.globalIndexLocation,
                "the global policy index lists policy [$id], which has no content (7.x fails every request)",
            )
        }
    }

    private fun convertPolicy(entry: Source7x.Entry) {
        val id = entry.id
        val tree = policyTree(entry) ?: return
        val decoded =
            decode(entry.location, "policy [$id]") { V7JsonSerializer.treeToValue(tree, Policy::class.java) }
        val type = decoded?.type ?: tree.get(TYPE)?.takeIf { it.isString }?.let { typeOf(it.asString()) }
        val global = isGlobal(id, type, entry.location) ?: return
        val key = placement(id, global, tree, entry.location) ?: return
        val written = tree.get(STATEMENTS)?.values().orEmpty().indices.map { "${entry.location}#$.$STATEMENTS[$it]" }
        // Translated even when it does not decode, so one run reports every problem of the policy.
        val translated = translatePolicy(tree, written, entry.location)
        if (translated == null || decoded == null) {
            return
        }
        // The locations of the statements kept: a dropped one (PIP-18) has none in the 8.0 policy.
        val locations = translated.locations
        val definition = PolicyDefinition(
            PolicyId(id),
            attachable = !global,
            condition = translated.condition,
            translated.statements
        )
        if (!compiles(key, definition, locations)) {
            return
        }
        policies.getValue(key) += definition
        val kind = if (global) "global" else type.toString().lowercase()
        policyOrigins += Provenance.PolicyOrigin(key.toString(), id, "$kind policy [$id] at ${entry.location}", locations)
        if (global) {
            count("globalPolicies")
            val bindingId = Converter.globalBindingId(id)
            bindings.getValue(SnapshotKey.Platform) += Binding(
                BindingId(bindingId),
                SubjectRef.Anyone,
                BindingScope(Match.Any, Match.Any),
                PolicyId(id),
            )
            bindingOrigins += Provenance.BindingOrigin(key.toString(), bindingId, "global policy index entry [$id]")
        } else {
            count("attachablePolicies")
        }
    }

    /** The policy as a JSON object, or null when its id is reserved or its content is not its own (rejected). */
    private fun policyTree(entry: Source7x.Entry): ObjectNode? {
        val id = entry.id
        RESERVED.firstOrNull { id.startsWith(it) }?.let { prefix ->
            reject(
                RejectionKind.RESERVED_POLICY_ID,
                entry.location,
                "policy id [$id] starts with reserved prefix [$prefix]"
            )
            return null
        }
        val tree = objectOf(entry.bytes, entry.location, "policy [$id]") ?: return null
        if (namesAnotherId(tree, id)) {
            reject(RejectionKind.UNDECODABLE, entry.location, "policy [$id] holds content with id [${tree.get(ID)}]")
            return null
        }
        return tree
    }

    /** Whether the policy applies to everyone, as 7.x reads the index and the type; null when that is unclear. */
    private fun isGlobal(id: String, type: PolicyType?, location: String): Boolean? {
        val inIndex = id in source.globalIndex
        if (type == PolicyType.GLOBAL && !inIndex) {
            reject(
                RejectionKind.GLOBAL_TYPE_NOT_INDEXED,
                location,
                "policy [$id] is of type GLOBAL but not in the global policy index, so 7.x does not apply it to everyone",
            )
            return null
        }
        if (inIndex && type != null && type != PolicyType.GLOBAL) {
            warnings += Warning(
                location,
                "policy [$id] is in the global policy index but of type $type: 7.x does not apply it to everyone, " +
                    "so it is converted as an attachable policy",
            )
        }
        return inIndex && type == PolicyType.GLOBAL
    }

    /**
     * The condition and statements of a policy, or null when one of them or the condition is rejected. Everything is
     * translated even after a rejection, so one run reports every problem of the policy.
     */
    private fun translatePolicy(
        tree: JsonNode,
        locations: List<String>,
        location: String,
    ): TranslatedPolicy? {
        val condition = tree.get(CONDITION)
        val nodes = tree.get(STATEMENTS)?.values().orEmpty()
        scan.policyCondition(condition, "$location#$.$CONDITION")
        val translated = nodes.mapIndexed { i, statement -> translateStatement(statement, condition, locations[i]) }
        val kept = translated.indices.filter { translated[it] is StatementTranslator.Translation.Kept }
        val policy = statements.policy(
            condition,
            "$location#$.$CONDITION",
            kept.map { (translated[it] as StatementTranslator.Translation.Kept).statement },
        )
        return if (policy != null && translated.none { it == null }) {
            TranslatedPolicy(policy.first, policy.second, kept.map { locations[it] })
        } else {
            null
        }
    }

    /** Whether [tree] names an id other than the [id] its key or position names. */
    private fun namesAnotherId(tree: JsonNode, id: String): Boolean {
        val content = tree.get(ID)?.takeUnless { it.isNull } ?: return false
        return !content.isString || content.asString() != id
    }

    /** The document a policy goes into, or null when its owner names no tenant document (rejected). */
    private fun placement(id: String, global: Boolean, tree: JsonNode, location: String): SnapshotKey? {
        if (global || options.partition == Partition.PLATFORM) {
            return SnapshotKey.Platform
        }
        val tenantId = tree.get(TENANT_ID)?.takeIf { it.isString }?.asString().orEmpty()
        if (tenantId == TenantId.PLATFORM.value) {
            return SnapshotKey.Platform
        }
        if (tree.get(STATEMENTS)?.values()?.any(::isDeny) == true) {
            // Principals of other tenants may attach it in 7.x: in their own tenant's document only, they would lose
            // its DENY statements. It stays attachable to everyone.
            warnings += Warning(
                location,
                "policy [$id] of tenant [$tenantId] holds DENY statements: kept in the platform document, so " +
                    "principals of other tenants that attach it keep its DENY (--partition by-owner)",
            )
            return SnapshotKey.Platform
        }
        if (!SnapshotKey.Tenant.isValidTenantId(tenantId)) {
            reject(
                RejectionKind.OWNER_TENANT,
                location,
                "policy [${Identifiers.printable(id)}] has tenantId [${Identifiers.printable(tenantId)}], which names no " +
                    "tenant document (--partition by-owner)",
            )
            return null
        }
        if (!tenants.distinctInCase(tenantId, location)) {
            return null
        }
        val key = SnapshotKey.Tenant(tenantId)
        policies.getOrPut(key) { ArrayList() }
        bindings.getOrPut(key) { ArrayList() }
        scan.ownedByTenant(tenantId, location)
        return key
    }

    /** A 7.x statement as an 8.0 statement without its policy condition, or null when a part of it is rejected. */
    private fun translateStatement(
        statement: JsonNode,
        policyCondition: JsonNode?,
        location: String,
        name: String = statement.get(NAME)?.takeIf { it.isString }?.asString().orEmpty(),
    ): StatementTranslator.Translation? {
        if (!statement.isObject) {
            reject(RejectionKind.UNDECODABLE, location, "a statement is an object")
            return null
        }
        scan.statement(isDeny(statement), statement.get(ACTION), statement.get(CONDITION), policyCondition, location)
        return statements.translation(statement, location, name)
    }

    // ---- apps (RFC 0004 §5.1: app permissions and role grants) ----

    /** A permission of an app; [statement] null when it was an ALLOW 8.0 could never match, dropped (PIP-18). */
    private class AppPermissionStatement(
        val permissionId: String,
        val statement: StatementSource?,
        val location: String
    )

    private fun convertApp(app: Source7x.App) {
        count("apps")
        val appId = app.appId
        if (apps.repeated(appId, app.location) || !apps.distinctInCase(appId, app.location)) {
            return
        }
        val tree = appPermissionTree(app) ?: return
        val location = app.appPermission?.location ?: return
        val decoded = decode(location, "the app permission of [$appId]") {
            V7JsonSerializer.treeToValue(tree, AppPermission::class.java)
        }
        val appCondition = tree.get(CONDITION)
        val permissions = permissions(tree, location, appCondition)
        val grants = convertGrants(app, permissions?.keys)
        // Translated over every permission once, so its rejections are reported even if nothing is granted.
        val guard = statements.policy(
            appCondition,
            "$location#$.$CONDITION",
            permissions?.values?.mapNotNull {
                it.statement
            }.orEmpty()
        )
        if (permissions == null || grants == null) {
            return
        }
        if (guard == null || decoded == null) {
            return
        }
        val key = SnapshotKey.App(appId)
        val granted = grants.flatMap { it.permissionIds }.toSet()
        val appPolicies = permissionPolicies(key, permissions, granted, appCondition, "$location#$.$CONDITION") ?: return
        policies[key] = appPolicies.toMutableList()
        bindings[key] = grants.flatMap { it.bindings }.toMutableList()
        catalogs[key] = catalogOf(tree)
        count("permissionPolicies", appPolicies.size)
    }

    /**
     * The app permission as a JSON object; null when the app id is rejected, the content is not its own, or there is
     * none, whose grants 7.x ignores (dropped with a warning).
     */
    private fun appPermissionTree(app: Source7x.App): ObjectNode? {
        val appId = app.appId
        if (!SnapshotKey.App.isValidAppId(appId)) {
            reject(
                RejectionKind.INVALID_ID,
                app.location,
                "app id [${Identifiers.printable(appId)}] is `*`, blank, longer than ${ScopeResolver.MAX_APP_ID_LENGTH} " +
                    "characters, or contains `:`, a control character or an unpaired surrogate: 8.0 reads `*` as any " +
                    "app, and names no app document by the others",
            )
            return null
        }
        val appPermission = app.appPermission
        if (appPermission == null) {
            if (app.grants.isNotEmpty()) {
                warnings += Warning(
                    app.location,
                    "app [$appId] has grants ${app.grants.map { grantKey(it) }} but no app permission: 7.x ignores " +
                        "them, so they are dropped",
                )
            }
            return null
        }
        val tree = objectOf(appPermission.bytes, appPermission.location, "the app permission of [$appId]") ?: return null
        if (namesAnotherId(tree, appId)) {
            reject(
                RejectionKind.UNDECODABLE,
                appPermission.location,
                "app permission [$appId] holds content with id [${tree.get(ID)}]"
            )
            return null
        }
        return tree
    }

    /**
     * A permission policy for each granted permission, and one holding every permission for a `*` grant, in the
     * order of `permissionIndexer`, as 7.x visits the permissions of a `*` grant (RFC 0002 S5); null when one does
     * not compile.
     */
    private fun permissionPolicies(
        key: SnapshotKey.App,
        permissions: Map<String, AppPermissionStatement>,
        granted: Set<String>,
        appCondition: JsonNode?,
        conditionLocation: String,
    ): List<PolicyDefinition>? {
        val appId = key.appId
        val sources = permissions.values.filter { it.permissionId in granted }
            .map { Triple(it.permissionId, listOf(it), "permission [${it.permissionId}] of app [$appId]") }
            .toMutableList()
        if (AppRolePermission.ALL_PERMISSION_ID in granted) {
            sources += Triple(AppRolePermission.ALL_PERMISSION_ID, permissions.values.toList(), "every permission of app [$appId] (grant `*`)")
        }
        val definitions = sources.map { (permissionId, members, origin) ->
            val id = Converter.permissionPolicyId(appId, permissionId)
            // The app condition is the policy condition of every permission policy (RFC 0004 §5.1).
            val kept = members.filter { it.statement != null }
            val (condition, guarded) = statements.policy(
                appCondition, conditionLocation,
                kept.map {
                    it.statement!!
                }
            ) ?: return null
            val definition = PolicyDefinition(PolicyId(id), attachable = false, condition = condition, guarded)
            val locations = kept.map { it.location }
            if (!compiles(key, definition, locations)) {
                return null
            }
            policyOrigins += Provenance.PolicyOrigin(key.toString(), id, origin, locations)
            definition
        }
        return definitions
    }

    /** The permissions of an app permission by id, in `permissionIndexer` order; null when one is rejected. */
    private fun permissions(
        tree: JsonNode,
        location: String,
        appCondition: JsonNode?
    ): Map<String, AppPermissionStatement>? {
        val permissions = LinkedHashMap<String, AppPermissionStatement>()
        var ok = true
        tree.get(GROUPS)?.values()?.forEachIndexed { g, group ->
            group.get(PERMISSIONS)?.values()?.forEachIndexed { p, permission ->
                val at = "$location#$.$GROUPS[$g].$PERMISSIONS[$p]"
                val permissionId = permission.get(ID)?.takeIf { it.isString }?.asString()
                when {
                    permissionId.isNullOrEmpty() || permissionId == AppRolePermission.ALL_PERMISSION_ID -> {
                        reject(
                            RejectionKind.INVALID_ID,
                            at,
                            "permission id [${permissionId.orEmpty()}] is empty or `*`"
                        )
                        ok = false
                    }

                    permissionId in permissions -> {
                        reject(RejectionKind.DUPLICATE_ID, at, "permission [$permissionId] is defined more than once")
                        ok = false
                    }

                    else -> {
                        // An app condition guards every permission statement, as a policy condition does.
                        when (val statement = translateStatement(permission, appCondition, at, name = permissionId)) {
                            null -> ok = false
                            is StatementTranslator.Translation.Kept ->
                                permissions[permissionId] = AppPermissionStatement(permissionId, statement.statement, at)
                            // Still a permission grants may name: its policy holds no statement.
                            StatementTranslator.Translation.Dropped ->
                                permissions[permissionId] = AppPermissionStatement(permissionId, null, at)
                        }
                    }
                }
            }
        }
        scan.policyCondition(appCondition, "$location#$.$CONDITION")
        return if (ok) permissions else null
    }

    private class ConvertedGrant(val permissionIds: List<String>, val bindings: List<Binding>)

    /** Each grant's bindings, in the order it lists its permissions; null when one is rejected. */
    private fun convertGrants(app: Source7x.App, defined: Set<String>?): List<ConvertedGrant>? {
        val converted = app.grants.map { grant ->
            count("grants")
            grantIds(
                app.appId,
                grant,
                defined
            )?.let { ids -> ConvertedGrant(ids, grantBindings(app.appId, grant, ids)) }
        }
        return if (converted.any { it == null }) null else converted.filterNotNull()
    }

    /** The permission ids a grant lists, without repeats; null when the grant is rejected. */
    private fun grantIds(appId: String, grant: Source7x.Grant, defined: Set<String>?): List<String>? {
        if (!isValidGrant(grant)) {
            reject(
                RejectionKind.INVALID_ID,
                grant.location,
                "grant [${grantKey(grant)}]: a role id must be non-empty without `@`, a space id must not be `*`",
            )
            return null
        }
        val ids = permissionIdsOf(grant.bytes)
        if (ids == null) {
            reject(RejectionKind.UNDECODABLE, grant.location, "a grant is a JSON array of permission ids")
            return null
        }
        if (grant.spaceId.isNotEmpty()) {
            scan.spacedGrant(grant.roleId, grant.spaceId, grant.location)
        }
        val undefined = if (defined == null) {
            emptyList()
        } else {
            ids.filter {
                it != AppRolePermission.ALL_PERMISSION_ID && it !in defined
            }
        }
        if (undefined.isNotEmpty()) {
            reject(
                RejectionKind.UNDEFINED_PERMISSION,
                grant.location,
                "grant [${grantKey(grant)}] names permissions $undefined that app [$appId] does not define",
            )
            scan.undefinedPermission(undefined, grant.location)
            return null
        }
        return ids
    }

    private fun permissionIdsOf(bytes: ByteArray): List<String>? {
        val node = try {
            treeReader.readTree(bytes)
        } catch (_: tools.jackson.core.JacksonException) {
            return null
        }
        if (node == null || !node.isArray || !node.values().all { it.isString }) {
            return null
        }
        return node.values().map { it.asString() }.distinct()
    }

    /** One role binding per granted permission; the binding's number is the permission's place in the grant. */
    private fun grantBindings(appId: String, grant: Source7x.Grant, ids: List<String>): List<Binding> {
        val key = SnapshotKey.App(appId)
        return ids.mapIndexed { n, permissionId ->
            val bindingId = Converter.grantBindingId(grant.roleId, grant.spaceId, n)
            bindingOrigins += Provenance.BindingOrigin(
                key.toString(),
                bindingId,
                "app [$appId] role [${grant.roleId}] space [${grant.spaceId}] permission [$permissionId] at ${grant.location}",
            )
            Binding(
                BindingId(bindingId),
                SubjectRef.Role(null, RoleId(grant.roleId)),
                BindingScope(Match.Exactly(AppId(appId)), Match.Exactly(SpaceId(grant.spaceId))),
                PolicyId(Converter.permissionPolicyId(appId, permissionId)),
            )
        }
    }

    // ---- rate limit rules (RFC 0005 §7.3) ----

    private fun convertRateLimits() {
        val seen = HashSet<String>()
        val translation = RateLimitTranslation(
            statements,
            { kind, location, message -> reject(kind, location, message) }
        ) {
            changes += it
        }
        for (entry in source.rateLimitRules) {
            count("rateLimitRules")
            if (!seen.add(entry.id)) {
                reject(
                    RejectionKind.DUPLICATE_ID,
                    entry.location,
                    "rate limit rule [${entry.id}] is listed more than once"
                )
                continue
            }
            val tree = objectOf(entry.bytes, entry.location, "rate limit rule [${entry.id}]") ?: continue
            val rule = translation.translate(entry, tree) ?: continue
            // Compiled as a statement is, so a rule that would not match as written never gets written.
            val policy = PolicyDefinition(
                PolicyId("$RATE_LIMIT_POLICY${entry.id}"),
                attachable = false,
                condition = null,
                listOf(rule.statement)
            )
            if (compiles(SnapshotKey.Platform, policy, listOf(entry.location))) {
                rateLimits += rule
            }
        }
    }

    // ---- documents ----

    private fun documents(): Map<SnapshotKey, PolicyDocument> {
        val documents = LinkedHashMap<SnapshotKey, PolicyDocument>()
        // Declared by the platform document, so the documents compile by themselves (RFC 0006 §5.4.5).
        val country = conditions.readsCountry
        if (country) {
            changes += SemanticChange(
                "IP2REGION",
                CONVERSION,
                "statements read ext.ip2region.country, which the platform document declares: every host that enforces " +
                    "them needs cosec-ip2region, without which the attribute is unavailable and such a DENY is " +
                    "Indeterminate (503); an address no loaded database covers is unavailable too (ATR-9)",
            )
        }
        for ((key, keyPolicies) in policies) {
            val (kind, owner) = when (key) {
                is SnapshotKey.App -> DocumentKind.APP to key.appId
                is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
                else -> DocumentKind.PLATFORM to ""
            }
            val document = PolicyDocument(
                kind,
                owner,
                REVISION,
                options.publishedAt,
                keyPolicies,
                bindings[key].orEmpty(),
                catalogs[key],
                attributes = if (country && key == SnapshotKey.Platform) listOf(Converter.IP2REGION_COUNTRY) else null,
                rateLimits = rateLimits.of(key),
            )
            // Every policy compiled on its own; the whole document must too (ids, bindings, break-glass rules, and the
            // budgets of the whole: a refusal of the data, so it is reported and written like the others, #1247).
            try {
                compiler.compile(document)
                documents[key] = document
            } catch (error: DocumentCompileException) {
                error.errors.forEach {
                    reject(
                        RejectionKind.DOES_NOT_COMPILE,
                        originOf(policyOrigins, key, keyPolicies, it.path),
                        "the $key document: ${it.path}: ${it.message}"
                    )
                }
            }
        }
        return documents
    }

    /** Whether [policy] compiles in a document of [key]'s kind; its errors are rejected at [statements]' locations. */
    private fun compiles(key: SnapshotKey, policy: PolicyDefinition, statements: List<String>): Boolean {
        val (kind, owner) = when (key) {
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
            else -> DocumentKind.PLATFORM to ""
        }
        return try {
            compiler.compile(PolicyDocument(kind, owner, REVISION, options.publishedAt, listOf(policy), emptyList()))
            true
        } catch (error: DocumentCompileException) {
            for (compileError in error.errors) {
                val statement = STATEMENT_PATH.find(compileError.path)?.groupValues?.get(1)?.toInt()
                val location = statement?.let { statements.getOrNull(it) } ?: statements.firstOrNull().orEmpty()
                reject(RejectionKind.DOES_NOT_COMPILE, location, "${compileError.path}: ${compileError.message}")
            }
            false
        }
    }

    // ---- JSON ----

    private fun objectOf(bytes: ByteArray, location: String, what: String): ObjectNode? {
        val node = try {
            treeReader.readTree(bytes)
        } catch (_: tools.jackson.core.JacksonException) {
            reject(RejectionKind.UNDECODABLE, location, "$what is not valid JSON")
            return null
        }
        if (node !is ObjectNode) {
            reject(RejectionKind.UNDECODABLE, location, "$what is not a JSON object")
            return null
        }
        return node
    }

    /**
     * [read] decodes 7.x JSON as 7.x does; a failure, such as a matcher type no factory is registered for, rejects. A
     * missing class (a matcher whose library is not on the classpath) is reported the same way: the data may be fine,
     * but this run cannot tell.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> decode(location: String, what: String, read: () -> T): T? = try {
        read()
    } catch (error: Exception) {
        val cause = generateSequence<Throwable>(error) { it.cause }.last()
        reject(RejectionKind.UNDECODABLE, location, "$what cannot be decoded by 7.x: ${cause.message}")
        null
    } catch (error: LinkageError) {
        reject(
            RejectionKind.UNDECODABLE,
            location,
            "$what needs a class missing from the classpath (${error.message}): add its library with -cp",
        )
        null
    }

    companion object {
        const val SCHEMA_7 = "7"

        /** The location of what the conversion as a whole reports. */
        const val CONVERSION = "conversion"
        const val REVISION = 1L
        const val RATE_LIMIT_POLICY = "rate-limit/"
        val RESERVED = Converter.RESERVED_PREFIXES
        val STATEMENT_PATH = Regex("""^\$\.policies\[\d+]\.statements\[(\d+)]""")
        const val ID = "id"
        const val TYPE = "type"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val TENANT_ID = "tenantId"
        const val CONDITION = "condition"
        const val STATEMENTS = "statements"
        const val ACTION = "action"
        const val EFFECT = "effect"
        const val GROUPS = "groups"
        const val PERMISSIONS = "permissions"
        const val SPACED = "spaced"
    }
}

/** A policy translated: its condition, the statements kept, and where each of them came from. */
private class TranslatedPolicy(
    val condition: Condition?,
    val statements: List<StatementSource>,
    val locations: List<String>,
)

// Pure helpers of [Run]: what they read is their arguments alone.

private fun isValidGrant(grant: Source7x.Grant): Boolean {
    val separator = SpacedRoleId.SPACE_ID_SEPARATOR
    val validRole = grant.roleId.isNotEmpty() && !grant.roleId.contains(separator)
    return validRole && grant.spaceId != Match.WILDCARD && !grant.spaceId.contains(separator)
}

private fun catalogOf(tree: JsonNode): Catalog = Catalog(
    tree.get(Run.GROUPS)?.values().orEmpty().map { group ->
        CatalogGroup(
            name = group.get(Run.NAME)?.asString().orEmpty(),
            description = group.get(Run.DESCRIPTION)?.asString()?.ifEmpty { null },
            spaced = group.get(Run.SPACED)?.asBoolean() ?: false,
            permissions = group.get(Run.PERMISSIONS)?.values().orEmpty().map { permission ->
                CatalogPermission(
                    id = permission.get(Run.ID).asString(),
                    name = permission.get(Run.NAME)?.asString()?.ifEmpty { null },
                    description = permission.get(Run.DESCRIPTION)?.asString()?.ifEmpty { null },
                )
            },
        )
    },
)

private fun isDeny(statement: JsonNode): Boolean =
    statement.get(Run.EFFECT)?.takeIf { it.isString }?.asString()?.equals("deny", ignoreCase = true) == true

@Suppress("SwallowedException")
private fun typeOf(text: String): PolicyType? = try {
    PolicyType.valueOf(text.uppercase())
} catch (_: IllegalArgumentException) {
    null
}

private fun grantKey(grant: Source7x.Grant): String =
    if (grant.spaceId.isEmpty()) grant.roleId else "${grant.roleId}${SpacedRoleId.SPACE_ID_SEPARATOR}${grant.spaceId}"
