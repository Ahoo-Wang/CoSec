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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.engine.AppPermissionGroup
import me.ahoo.cosec.authorization.engine.LayerReads
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicyGroup
import me.ahoo.cosec.authorization.engine.PolicySetLoader
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.authorization.engine.SnapshotPolicySetLoader
import me.ahoo.cosec.authorization.engine.StatementRule
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.RuleEntry
import me.ahoo.cosec.engine.RuleGroup
import me.ahoo.cosec.engine.UnresolvedGrant
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.EntryOrigin
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.Snapshot
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotEntry
import me.ahoo.cosec.snapshot.SnapshotKey
import reactor.core.publisher.Mono
import tools.jackson.core.type.TypeReference
import java.time.Instant
import kotlin.random.Random

/*
 * RFC 0003 §8.4 P5: the PolicySet a snapshot gives equals the one repositories holding the same data give. The data
 * model, the generator and the comparison live here so that both cosec-core (against a model of 7.0's repositories)
 * and cosec-cocache (against the real Redis repositories on CoCache) run the same cases.
 */

val PUBLISHED_AT: Instant = Instant.parse("2026-10-21T08:00:00Z")

/** A schema-1 document whose body holds the header fields followed by [sections]. */
fun snapshotDocument(
    revision: Long,
    sections: String,
    schema: Int = SnapshotDocument.CURRENT_SCHEMA
): SnapshotDocument =
    SnapshotDocument(
        schema,
        revision,
        PUBLISHED_AT,
        """{"schema":$schema,"revision":$revision,"publishedAt":"$PUBLISHED_AT",$sections}""".encodeToByteArray(),
    )

/** A policy as JSON. */
fun policyJson(
    id: String,
    type: String = "custom",
    statements: String = """{"name":"s0","effect":"allow","action":"*"}""",
    condition: String = """{"all":{}}""",
): String =
    """{"id":"$id","name":"$id","category":"","description":"","type":"$type","tenantId":"t","condition":$condition,"statements":[$statements]}"""

/** An app permission as JSON with one group of [permissions]. */
fun appPermissionJson(id: String, permissions: String, condition: String = """{"all":{}}"""): String =
    """{"id":"$id","condition":$condition,"groups":[{"name":"g","description":"","permissions":[$permissions]}]}"""

/** A permission as JSON. */
fun permissionJson(id: String, action: String = "*", effect: String = "allow"): String =
    """{"id":"$id","name":"$id","description":"","effect":"$effect","action":"$action"}"""

/**
 * 7.x data as JSON, the way Redis holds it: every policy by id, the global policy index, each app's permission and
 * each role's grants by `role` or `role@space`. Both the snapshot documents and the repositories are built from the
 * same JSON, so they hold the same data.
 */
class LegacyData(
    /** Policy JSON by id, in the order the document lists them (by id, RFC 0003 §7 X3). */
    val policies: Map<String, String> = emptyMap(),
    /** The ids in the global policy index, in the order the repository reads them. */
    val globalIndex: List<String> = emptyList(),
    /** App permission JSON by app id. */
    val appPermissions: Map<AppId, String> = emptyMap(),
    /** Grant JSON (an array of permission ids) by app id, then by `role` or `role@space`. */
    val grants: Map<AppId, Map<String, String>> = emptyMap(),
    /** When set, every read of a policy fails with it, and the policies document is failed with it. */
    val policiesFailure: Throwable? = null,
    /** Read failures by app: every read of the app fails with it, and its document is failed with it. */
    val appFailures: Map<AppId, Throwable> = emptyMap(),
) {
    /** The policies document; null when no policy is stored. */
    fun policiesDocument(revision: Long = 1): SnapshotDocument? {
        if (policies.isEmpty()) {
            return null
        }
        return snapshotDocument(revision, """"policies":[${policies.values.joinToString(",")}]""")
    }

    /** The document of [appId]; null when the app has no app permission. */
    fun appDocument(appId: AppId, revision: Long = 1): SnapshotDocument? {
        val appPermission = appPermissions[appId] ?: return null
        val grants = grants[appId].orEmpty().entries.joinToString(",") { (role, json) -> """"$role":$json""" }
        return snapshotDocument(revision, """"appPermission":$appPermission,"grants":{$grants}""")
    }

    /** Stores every document in [store]. */
    fun publish(store: InMemorySnapshotDocumentStore, revision: Long = 1) {
        policiesDocument(revision)?.let { store.put(SnapshotKey.Policies, it) }
        for (appId in appPermissions.keys) {
            appDocument(appId, revision)?.let { store.put(SnapshotKey.App(appId), it) }
        }
    }

    /**
     * A model of 7.0's `RedisPolicyRepository`: decoded on every read, an undecodable policy fails the read. The real
     * repository runs the same cases in cosec-cocache.
     */
    val policyRepository: PolicyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.defer {
            policiesFailure?.let { return@defer Mono.error(it) }
            Mono.just(globalIndex.map { decodePolicy(policies.getValue(it)) }.filter { it.type == PolicyType.GLOBAL })
        }

        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.defer {
            policiesFailure?.let { return@defer Mono.error(it) }
            Mono.just(policyIds.mapNotNull { id -> policies[id]?.let(::decodePolicy) })
        }

        override fun setPolicy(policy: Policy): Mono<Void> = throw UnsupportedOperationException()
    }

    /** A model of 7.0's `RedisAppRolePermissionRepository`. */
    val appRolePermissionRepository: AppRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: AppId,
            spaceId: SpaceId,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> =
            Mono.defer {
                if (!SnapshotKey.App.isValidAppId(appId)) {
                    return@defer Mono.empty()
                }
                appFailures[appId]?.let { return@defer Mono.error(it) }
                val appPermission = appPermissions[appId]?.let {
                    CoSecJsonSerializer.readValue(it, AppPermission::class.java)
                } ?: return@defer Mono.empty()
                val appGrants = grants[appId].orEmpty()
                val decoded = roleIds.associateWith { roleId ->
                    val key = grantKey(roleId, spaceId)
                    appGrants[key]?.let(::decodeGrants)
                }
                val rolePermissions: List<RolePermission> =
                    decoded.mapNotNull { (roleId, permissions) -> permissions?.let { RolePermissionData(roleId, it) } }
                Mono.just(AppRolePermissionData(appPermission, rolePermissions))
            }
    }

    companion object {
        private val GRANTS = object : TypeReference<Set<PermissionId>>() {}

        fun decodePolicy(json: String): Policy = CoSecJsonSerializer.readValue(json, Policy::class.java)

        /** As 7.0's `RolePermissionCache` codec decodes grants: into a `HashSet`, so in hash order. */
        fun decodeGrants(json: String): Set<PermissionId> = CoSecJsonSerializer.readValue(json, GRANTS)

        /** The key of a role's grants in a space, as 7.0's `AppSpacedRoleId` writes it without the app. */
        fun grantKey(roleId: RoleId, spaceId: SpaceId): String =
            if (spaceId == SpaceIdCapable.DEFAULT) roleId else "$roleId@$spaceId"
    }
}

/** One generated case: the data, and a request with its principal. */
class LegacyCase(val data: LegacyData, val principal: CoSecPrincipal, val request: Request) {
    fun context(): SecurityContext = SimpleSecurityContext(principal)
}

/**
 * Generates random 7.x data and requests (RFC 0003 §8.2), from a fixed [seed]: policies of every type, some
 * undecodable, some without a type; app catalogs with ids repeated across groups and more than 64 permissions;
 * grants in bare and spaced roles, with wildcards, unknown and repeated ids and lists longer than 64 in any space;
 * documents that cannot be read or decoded; principals and requests whose reads throw.
 *
 * [brokenEntries] and [failedDocuments] are off where the repositories cannot hold such data (real CoCache caches
 * only store what they can decode).
 */
class LegacyCases(seed: Int, private val brokenEntries: Boolean = true, private val failedDocuments: Boolean = true) {
    private val random = Random(seed)
    private val compiler = PolicySnapshotCompiler()

    private val actions = listOf(
        """"/a/{id}/**"""",
        """"/a/**"""",
        """"/b/**"""",
        """"*"""",
        """["/b/**", "/c/{id}"]""",
        """{ "path": { "pattern": "/c/**", "method": "GET" } }""",
    )
    private val conditions = listOf(
        """{ "all": {} }""",
        """{ "authenticated": {} }""",
        """{ "in": { "part": "request.method", "value": ["GET", "PUT"] } }""",
        """{ "inRole": { "value": "r1@s1" } }""",
        """{ "bool": { "or": [ { "inRole": { "value": "r2" } }, { "eq": { "part": "request.method", "value": "POST" } } ] } }""",
    )

    /** A matcher type no factory knows: the entry decodes as 7.0's cache would fail to. */
    private val unknownCondition = """{ "noSuchMatcher": {} }"""

    private val roles = listOf("r1", "r2", "r3", "r1@s1", "r2@s2", "r3@s1", "bad@", "a@b@c", "")
    private val spaces = listOf("", "s1", "s2")
    private val appIds = listOf("app", "app", "app", "other", "unknown", "a:b", "")

    private fun chance(oneIn: Int) = random.nextInt(oneIn) == 0

    private fun effect() = if (chance(3)) "deny" else "allow"

    private fun condition(): String = if (brokenEntries && chance(60)) unknownCondition else conditions.random(random)

    private fun statements(): String = List(random.nextInt(4)) { index ->
        """{"name":"s$index","effect":"${effect()}","action":${actions.random(random)},"condition":${condition()}}"""
    }.joinToString(",")

    private fun policies(): Map<String, String> = List(random.nextInt(7)) { "p$it" }.associateWith { id ->
        if (brokenEntries && chance(40)) {
            // No type: undecodable, and nothing tells whether it is global.
            return@associateWith """{"id":"$id","name":"$id","tenantId":"t","statements":[${statements()}]}"""
        }
        val type = when (random.nextInt(4)) {
            0 -> "global"
            1 -> "system"
            else -> "custom"
        }
        policyJson(id, type, statements(), if (chance(5)) condition() else conditions[0])
    }

    private fun permission(id: String) =
        """{"id":"$id","name":"$id","description":"","effect":"${effect()}","action":${actions.random(
            random
        )},"condition":${condition()}}"""

    /** A catalog in two groups; the second sometimes repeats ids of the first, with other rules. */
    private fun appPermission(appId: String, permissionIds: List<String>): String {
        if (brokenEntries && chance(40)) {
            return """{"id":"$appId","condition":$unknownCondition,"groups":[]}"""
        }
        val (first, second) = permissionIds.partition { random.nextBoolean() }
        val repeated = if (chance(4)) first.filter { random.nextBoolean() } else emptyList()
        val groups = listOf(first, second + repeated).mapIndexed { index, ids ->
            """{"name":"g$index","description":"","permissions":[${ids.joinToString(",") { permission(it) }}]}"""
        }
        val guard = if (chance(5)) condition() else conditions[0]
        return """{"id":"$appId","condition":$guard,"groups":[${groups.joinToString(",")}]}"""
    }

    /** Grants of bare and spaced roles; long lists exceed the shared grant-position cache (64) in any space. */
    private fun grants(permissionIds: List<String>, long: Boolean): Map<String, String> {
        val keys = listOf("r1", "r2", "r3", "r1@s1", "r2@s2", "r3@s1", "r1@s2").filter { long || !chance(4) }
        return keys.associateWith { key ->
            if (brokenEntries && chance(50)) {
                return@associateWith """{"not":"an array"}"""
            }
            val granted = when {
                long && (key == "r1" || key == "r2" || key == "r1@s1") ->
                    permissionIds.shuffled(random).take(65 + random.nextInt(permissionIds.size - 64))
                chance(6) -> listOf("*")
                chance(6) -> listOf("perm0", "*")
                else -> (permissionIds + "ghost").shuffled(random).filter { random.nextBoolean() }
            }
            val repeated = if (granted.isNotEmpty() && chance(5)) granted + granted.first() else granted
            repeated.joinToString(",", "[", "]") { "\"$it\"" }
        }
    }

    /** A failure of a whole document: unreadable, or stored but not decodable (the compiler's own error). */
    private fun documentFailure(key: SnapshotKey): Throwable? {
        if (!failedDocuments || !chance(30)) {
            return null
        }
        if (random.nextBoolean()) {
            return IllegalStateException("store down")
        }
        val malformed = snapshotDocument(1, """"policies":[],"policies":[]""")
        val compile: () -> Any = when (key) {
            is SnapshotKey.App -> ({ compiler.app.compile(key, malformed) })
            SnapshotKey.Policies -> ({ compiler.policies.compile(key, malformed) })
        }
        return runCatching(compile).exceptionOrNull()
    }

    private fun data(long: Boolean): LegacyData {
        val policies = if (chance(15)) emptyMap() else policies()
        // 7.0 only lists global policies in the index; one without a type may have been listed, and the snapshot
        // treats it as possibly global, so the data that matches it lists it.
        val globalIndex = policies.filterValues { it.contains("\"type\":\"global\"") || !it.contains("\"type\"") }.keys
        val appPermissions = mutableMapOf<String, String>()
        val grants = mutableMapOf<String, Map<String, String>>()
        val appFailures = mutableMapOf<String, Throwable>()
        for (appId in listOf("app", "other")) {
            if (chance(6)) {
                continue
            }
            val permissionIds = List(if (long) 66 + random.nextInt(10) else random.nextInt(6)) { "perm$it" }
            appPermissions[appId] = appPermission(appId, permissionIds)
            grants[appId] = grants(permissionIds, long)
            documentFailure(SnapshotKey.App(appId))?.let { appFailures[appId] = it }
        }
        return LegacyData(
            policies,
            globalIndex.toList(),
            appPermissions,
            grants,
            documentFailure(SnapshotKey.Policies),
            appFailures
        )
    }

    fun next(): LegacyCase {
        val long = chance(8)
        val data = data(long)
        val policyIds = data.policies.keys.filter { random.nextBoolean() }.toMutableSet()
        if (chance(10)) {
            policyIds += "missing"
        }
        val principal = SimplePrincipal(
            id = if (chance(6)) CoSecPrincipal.ANONYMOUS_ID else "user",
            policies = policyIds,
            roles = roles.filter { chance(3) }.toSet() + if (long) setOf("r1", "r2", "r1@s1") else emptySet(),
        )
        val request = EvaluateRequest(
            path = "/a/1",
            headers = mapOf(
                AppIdCapable.APP_ID_KEY to appIds.random(random),
                SpaceIdCapable.SPACE_ID_KEY to if (long) listOf("", "s1").random(random) else spaces.random(random),
            ).filterValues { it.isNotEmpty() },
        )
        return LegacyCase(
            data,
            ThrowingPrincipal(principal, throwOnPolicies = chance(30), throwOnRoles = chance(30)),
            ThrowingRequest(request, throwOnSpace = chance(30), throwOnApp = chance(30)),
        )
    }
}

/** A principal whose policies or roles cannot be read. */
class ThrowingPrincipal(
    private val delegate: CoSecPrincipal,
    private val throwOnPolicies: Boolean,
    private val throwOnRoles: Boolean,
) : CoSecPrincipal by delegate {
    override val policies: Set<String>
        get() = if (throwOnPolicies) throw IllegalStateException("policies") else delegate.policies
    override val roles: Set<String>
        get() = if (throwOnRoles) throw IllegalStateException("roles") else delegate.roles
}

/** A request whose space or app cannot be read. */
class ThrowingRequest(
    private val delegate: Request,
    private val throwOnSpace: Boolean,
    private val throwOnApp: Boolean,
) : Request by delegate {
    override val spaceId: String
        get() = if (throwOnSpace) throw IllegalStateException("space") else delegate.spaceId
    override val appId: String
        get() = if (throwOnApp) throw IllegalStateException("app") else delegate.appId
}

/**
 * The structure of a [PolicySet] (RFC 0003 §3.5), comparable across loaders: no object identities, only content. The
 * layers' kinds and order; absent reasons; a failure's innermost cause, by class and message; the missing policies in
 * order; each group's id, guard (as JSON) and the entries it visits, in order. Every group is visited twice, and both
 * passes must see the very same entries.
 */
object PolicySetParity {
    private val compiler = PolicySnapshotCompiler()

    /** What [PolicySetLoader] builds over the repositories for [request]. */
    fun ofRepositories(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        request: Request,
        context: SecurityContext,
    ): List<Any?> {
        val authzRequest = LegacyFacts.authzRequest(request, context)
        return describe(PolicySetLoader(policyRepository, appRolePermissionRepository).load(authzRequest).block()!!)
    }

    /** What [SnapshotPolicySetLoader] builds over the snapshot of [data] for [request]. */
    fun ofSnapshot(data: LegacyData, request: Request, context: SecurityContext): List<Any?> =
        ofSnapshot(request, context) { key ->
            when (key) {
                SnapshotKey.Policies -> data.policiesFailure?.let { Result.failure(it) }
                    ?: Result.success(data.policiesDocument())

                is SnapshotKey.App -> data.appFailures[key.appId]?.let { Result.failure(it) }
                    ?: Result.success(data.appDocument(key.appId))
            }
        }

    /**
     * The snapshot side over documents from anywhere, such as a real store: [document] answers the document stored
     * under a key (null when none is), or the failure reading it.
     */
    fun ofSnapshot(
        request: Request,
        context: SecurityContext,
        document: (SnapshotKey) -> Result<SnapshotDocument?>,
    ): List<Any?> {
        val facts = LegacyFacts.authzRequest(request, context).host as LegacyFacts
        val reads = LayerReads.of(facts)
        val policies = stateOf(SnapshotKey.Policies, document(SnapshotKey.Policies)) { key, stored ->
            compiler.policies.compile(key, stored)
        }
        val app = reads.appDocument?.let { appId ->
            val key = SnapshotKey.App(appId)
            entry(key, stateOf(key, document(key)) { appKey, stored -> compiler.app.compile(appKey, stored) })
        }
        return describe(SnapshotPolicySetLoader.load(reads, Snapshot(entry(SnapshotKey.Policies, policies), app)))
    }

    private fun <T : Any> stateOf(
        key: SnapshotKey,
        read: Result<SnapshotDocument?>,
        compile: (SnapshotKey, SnapshotDocument) -> T,
    ): DocumentState<T> = read.fold(
        onSuccess = { document -> document?.let { compiled { compile(key, it) } } ?: DocumentState.Absent },
        onFailure = { DocumentState.Failed(it) },
    )

    @Suppress("TooGenericExceptionCaught")
    private fun <T : Any> compiled(compile: () -> T): DocumentState<T> = try {
        DocumentState.Present(compile())
    } catch (error: Exception) {
        DocumentState.Failed(error)
    }

    private fun <T : Any> entry(key: SnapshotKey, state: DocumentState<T>): SnapshotEntry<T> {
        val present = state is DocumentState.Present
        return SnapshotEntry(
            key,
            state,
            if (present) 1 else 0,
            null,
            PUBLISHED_AT,
            if (present) EntryOrigin.DOCUMENT else EntryOrigin.ABSENT
        )
    }

    fun describe(policies: PolicySet): List<Any?> = policies.layers.map { layer ->
        when (layer) {
            is LayerInput.Absent -> listOf(layer.kind, "absent", layer.reason)
            is LayerInput.Unavailable ->
                rootCause(
                    layer.error
                ).let { listOf(layer.kind, "unavailable", it.javaClass, withoutLocation(it.message)) }
            is LayerInput.Loaded -> listOf(
                layer.kind,
                "loaded",
                layer.missing?.policyIds?.toList(),
                layer.groups.map { describe(it) },
            )
        }
    }

    private fun describe(group: RuleGroup): List<Any?> {
        val guard = when (group) {
            is PolicyGroup -> CoSecJsonSerializer.writeValueAsString(group.policy)
            is AppPermissionGroup -> CoSecJsonSerializer.writeValueAsString(group.appPermission)
            else -> error("Unexpected group $group")
        }
        val first = entries(group)
        val second = entries(group)
        check(first.size == second.size) { "Group [${group.id}] visited ${first.size} entries, then ${second.size}." }
        for ((a, b) in first.zip(second)) {
            check(a.first === b.first) { "Group [${group.id}] changed an entry between passes." }
        }
        return listOf(group.id, guard, first.map { describe(it.first, it.second) })
    }

    private fun entries(group: RuleGroup): List<Pair<RuleEntry, String?>> {
        val entries = ArrayList<Pair<RuleEntry, String?>>()
        group.forEachRule { entry, grantedVia ->
            entries.add(entry to grantedVia)
            false
        }
        return entries
    }

    private fun describe(entry: RuleEntry, grantedVia: String?): List<Any?> = when (entry) {
        is StatementRule -> listOf(
            grantedVia,
            entry.effect,
            entry.index,
            entry.name,
            entry.id,
            entry.policy?.id,
            CoSecJsonSerializer.writeValueAsString(entry.statement),
        )

        is UnresolvedGrant -> listOf(grantedVia, entry.permissionId, entry.error.javaClass, entry.error.message)
        else -> error("Unexpected entry $entry")
    }

    /**
     * Jackson appends where in the input it failed, which differs between decoding a stored value and a tree read
     * from a document; the rest of the message is the same.
     */
    private fun withoutLocation(message: String?): String? = message?.replace(JACKSON_LOCATION, "")

    private val JACKSON_LOCATION = Regex("""\s+at \[(Source:|No location information).*""", RegexOption.DOT_MATCHES_ALL)

    private fun rootCause(error: Throwable): Throwable {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}
