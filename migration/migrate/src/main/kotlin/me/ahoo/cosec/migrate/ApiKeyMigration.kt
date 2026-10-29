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

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * An API key as it was stored before RFC 0009: it granted roles, attached policies and carried attributes of its own,
 * and its tenant was optional. [json] is exactly what was read, so a rewrite can be made only over it (review M2).
 */
class LegacyApiKey(
    val hash: String,
    val id: String,
    val principalId: String,
    val tenantId: String?,
    val roles: List<String>,
    val policies: List<String>,
    val attributes: Map<String, String>,
    val expiresAt: Instant?,
    val enabled: Boolean,
    val json: String = "",
) {
    /** The tenant the key is in: its own, or the default tenant when it has none. */
    val tenant: TenantId get() = tenantId?.takeIf { it.isNotEmpty() }?.let(::TenantId) ?: TenantId.DEFAULT

    /** Whether the key worked at [now]: a key that did not grants nothing anyone should gain. */
    fun usableAt(now: Instant): Boolean = enabled && (expiresAt == null || now.isBefore(expiresAt))

    companion object {
        private val FIELDS =
            setOf("id", "principalId", "tenantId", "roles", "policies", "attributes", "expiresAt", "enabled")

        /** Whether [json] is a key stored in the format of RFC 0009 already (`"format"` present). */
        fun isConverted(json: String): Boolean = (tree(json) as? ObjectNode)?.has("format") == true

        /** Reads a key stored before RFC 0009; anything this format does not have is refused, never dropped. */
        fun parse(hash: String, json: String): LegacyApiKey {
            val node = tree(json)
            require(node is ObjectNode) { "not a stored API key" }
            val unknown = node.propertyNames().toSet() - FIELDS
            require(unknown.isEmpty()) { "unknown fields $unknown" }
            return LegacyApiKey(
                hash = hash,
                id = node.text("id") ?: throw IllegalArgumentException("no id"),
                principalId = node.text("principalId") ?: throw IllegalArgumentException("no principalId"),
                tenantId = node.text("tenantId"),
                roles = node.strings("roles"),
                policies = node.strings("policies"),
                attributes = node.get("attributes")?.takeUnless { it.isNull }?.properties()
                    ?.associate { (name, value) -> name to value.asString() }.orEmpty(),
                expiresAt = node.text("expiresAt")?.let(Instant::parse),
                enabled = node.get("enabled")?.takeUnless { it.isNull }?.booleanValue() ?: true,
                json = json,
            )
        }

        /** [json] as a tree; JSON that cannot be read is an [IllegalArgumentException], like any other refusal. */
        private fun tree(json: String): JsonNode = try {
            CoSecJson.mapper.readTree(json)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("not JSON: ${error.originalMessage}", error)
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

        private fun JsonNode.strings(field: String): List<String> =
            get(field)?.takeUnless { it.isNull }?.values()?.map { it.asString() }.orEmpty()
    }
}

/** What the published documents say, as the engine reads them, for the plan. */
interface PublishedDocuments {
    /**
     * Whether [policy] attached in [tenant] resolves exactly as the engine resolves an attachment (`CandidateOrder`):
     * the tenant's own policy or the platform's, attachable, and when both define it, with the same content. Anything
     * else was never a grant: the engine answers indeterminate for it (RFC 0004 §6.4).
     */
    fun attachable(tenant: TenantId, policy: PolicyId): Boolean

    /** The ids of the principal and break-glass bindings that name [principal] in [tenant], in any document. */
    fun principalGrants(tenant: TenantId, principal: String): List<String>

    /** Whether any document binds [role]: a per-key role must not take an existing role's name. */
    fun boundRole(role: RoleId): Boolean
}

/**
 * `cosec-migrate api-keys` (RFC 0009 §4.4, review H2 and M1): turns keys that granted into keys of a principal, and says
 * what has to be granted and bound for each to keep exactly what it had. No key gains access, and no principal gains
 * any either unless asked to with [grantTo]. Each key goes one of three ways:
 *
 * - **In place**: the [memberships] export shows that its principal already holds every role the key named, and the key
 *   attached no policy. The key stays with its principal, capped to those roles.
 * - **A principal of its own** (the default otherwise): `apikey:<key id>`, a workload principal in the key's tenant that
 *   is to hold exactly the key's roles, with the key's attached policies bound to it by name. The key moves to it,
 *   uncapped. What depends on the principal's id changes, which the report says.
 * - **Granted to its principal**, only when [grantTo] names that principal: the union of its usable keys' roles goes
 *   to the principal, each key capped to its own roles and a role of its own (`<role prefix><key id>`) bound to its
 *   attached policies. The roles the principal newly gains are reported.
 *
 * In place and granted, a key is capped, so its principal's own bindings no longer reach it; and no API key gets
 * break-glass. Those are losses, never gains, and the report lists them per key.
 *
 * A key is rejected, and nothing is written, when it has attributes (a condition may read them), malformed roles, an id
 * with `@`, `*` or blanks, a role named with the role prefix, an attachment that does not resolve exactly (it made every
 * request of the key indeterminate, and dropping it could drop a DENY), or a per-key role an existing binding already
 * names.
 */
class ApiKeyMigration(
    private val documents: PublishedDocuments,
    /** Each principal's roles now, as `role` or `role@space`, from `--memberships`; null when not given. */
    private val memberships: Map<Pair<TenantId, String>, Set<String>>? = null,
    /** Principals whose keys' roles go to them; [ALL] for every principal. */
    private val grantTo: Set<String> = emptySet(),
    private val rolePrefix: String = DEFAULT_ROLE_PREFIX,
    private val now: Instant = Instant.now(),
) {
    init {
        require(rolePrefix.isNotEmpty() && '@' !in rolePrefix && rolePrefix.none { it.isWhitespace() }) {
            "The role prefix is not empty, and has no '@' or blank."
        }
    }

    fun plan(keys: List<LegacyApiKey>): ApiKeyMigrationPlan {
        val converted = ArrayList<ConvertedApiKey>()
        val rejections = ArrayList<String>()
        val grants = LinkedHashMap<Pair<TenantId, String>, LinkedHashSet<String>>()
        val principalBindings = ArrayList<PrincipalBinding>()
        val roleBindings = ArrayList<RoleBinding>()
        for (key in keys.sortedBy { it.id }) {
            val rejection = rejection(key)
            if (rejection != null) {
                rejections += "API key [${key.id}]: $rejection"
                continue
            }
            val conversion = when {
                granted(key) -> grant(key)
                staysInPlace(key) -> inPlace(key)
                else -> ownPrincipal(key)
            }
            converted += conversion.key
            principalBindings += conversion.principalBindings
            roleBindings += conversion.roleBindings
            conversion.grant?.let { (owner, roles) -> grants.getOrPut(owner) { LinkedHashSet() } += roles }
        }
        val granted = grants.map { (owner, roles) ->
            val held = memberships?.get(owner)
            PrincipalRoles(owner.first, owner.second, roles.toList(), held?.let { roles - it }?.toList())
        }
        return ApiKeyMigrationPlan(converted, granted, principalBindings, roleBindings, rejections)
    }

    private fun rejection(key: LegacyApiKey): String? {
        val claims = MembershipClaims.parse(key.roles, emptyList())
        val ownRole = rolePrefix + key.id
        return when {
            key.id.isBlank() || key.id.any { it == '@' || it == '*' || it.isWhitespace() } ->
                "its id has '@', '*' or a blank, so no principal or role can be named after it"
            key.attributes.isNotEmpty() ->
                "it has attributes ${key.attributes.keys}: 8.0 keys carry none, and a policy condition may read them. " +
                    "Remove them once no condition does, then run again."
            claims.malformed.isNotEmpty() -> "it has malformed roles ${claims.malformed}, which applied nowhere"
            key.roles.any { it.startsWith(rolePrefix) } ->
                "a role of it starts with the role prefix [$rolePrefix]: choose another --role-prefix"
            key.policies.any { !documents.attachable(key.tenant, PolicyId(it)) } ->
                "an attached policy (${key.policies.filterNot { documents.attachable(key.tenant, PolicyId(it)) }}) does " +
                    "not resolve: every request with the key was indeterminate. Fix the documents or the key first."
            granted(key) && key.policies.isNotEmpty() && documents.boundRole(RoleId(ownRole)) ->
                "a document already binds the role [$ownRole] its attachments would become"
            else -> null
        }
    }

    private fun granted(key: LegacyApiKey): Boolean = ALL in grantTo || key.principalId in grantTo

    private fun staysInPlace(key: LegacyApiKey): Boolean {
        val held = memberships?.get(key.tenant to key.principalId) ?: return false
        return key.policies.isEmpty() && covered(key.roles, held)
    }

    /** Whether every role of [roles] is in [held], by space and role. */
    private fun covered(roles: Collection<String>, held: Collection<String>): Boolean {
        val have = MembershipClaims.parse(held, emptyList()).roles
        return MembershipClaims.parse(roles, emptyList()).roles.all { (space, spaceRoles) ->
            have[space].orEmpty().containsAll(spaceRoles)
        }
    }

    private fun inPlace(key: LegacyApiKey): Conversion = Conversion(
        ConvertedApiKey(
            key,
            ApiKey(key.id, key.principalId, key.tenant.value, LinkedHashSet(key.roles), key.expiresAt, key.enabled),
            ConvertedApiKey.Mode.IN_PLACE,
            lost(key),
        ),
    )

    private fun ownPrincipal(key: LegacyApiKey): Conversion {
        val principal = OWN_PRINCIPAL_PREFIX + key.id
        val bindings = key.policies.distinct().map { policy ->
            PrincipalBinding(key.tenant, principal, PolicyId(policy), "$principal:$policy")
        }
        return Conversion(
            ConvertedApiKey(
                key,
                ApiKey(key.id, principal, key.tenant.value, null, key.expiresAt, key.enabled),
                ConvertedApiKey.Mode.OWN_PRINCIPAL,
                lost(key),
                PRINCIPAL_ID_CHANGES,
            ),
            principalBindings = bindings,
            grant = (key.tenant to principal) to key.roles.toList(),
        )
    }

    private fun grant(key: LegacyApiKey): Conversion {
        val cap = LinkedHashSet(key.roles)
        val bindings = ArrayList<RoleBinding>()
        if (key.policies.isNotEmpty()) {
            // Attachments applied in every space the scope resolved in: the default one, and those the key had roles in.
            val role = RoleId(rolePrefix + key.id)
            cap += role.value
            MembershipClaims.parse(key.roles, emptyList()).roles.keys.filter { it != SpaceId.DEFAULT }
                .forEach { cap += "${role.value}@${it.value}" }
            key.policies.distinct().forEach { bindings += RoleBinding(key.tenant, role, PolicyId(it), "${role.value}:$it") }
        }
        // A key that did not work gives its principal nothing (review M1 (3)); it is still converted, as it was.
        val grant = if (key.usableAt(now)) (key.tenant to key.principalId) to cap.toList() else null
        return Conversion(
            ConvertedApiKey(
                key,
                ApiKey(key.id, key.principalId, key.tenant.value, cap, key.expiresAt, key.enabled),
                ConvertedApiKey.Mode.GRANTED,
                lost(key),
            ),
            roleBindings = bindings,
            grant = grant,
        )
    }

    /** The principal's own grants the key held as that principal, and holds no longer. */
    private fun lost(key: LegacyApiKey): List<String> = documents.principalGrants(key.tenant, key.principalId)

    private class Conversion(
        val key: ConvertedApiKey,
        val principalBindings: List<PrincipalBinding> = emptyList(),
        val roleBindings: List<RoleBinding> = emptyList(),
        val grant: Pair<Pair<TenantId, String>, List<String>>? = null,
    )

    companion object {
        const val DEFAULT_ROLE_PREFIX: String = "api-key."
        const val OWN_PRINCIPAL_PREFIX: String = "apikey:"

        /** `--grant-to-principal all`. */
        const val ALL: String = "all"

        /** What changes for a key that moves to a principal of its own. */
        val PRINCIPAL_ID_CHANGES: List<String> = listOf(
            "the audit subject",
            "conditions on subject.id",
            "rate limit buckets keyed by the principal",
            "the sub of the forwarded CoSec-Identity",
        )
    }
}

class ConvertedApiKey(
    val legacy: LegacyApiKey,
    val key: ApiKey,
    val mode: Mode,
    /** The principal's own bindings (principal and break-glass) the key reached as that principal, and no longer does. */
    val lost: List<String>,
    /** What depends on the principal's id and changes with it; empty when the key keeps its principal. */
    val changes: List<String> = emptyList(),
) {
    enum class Mode { IN_PLACE, OWN_PRINCIPAL, GRANTED }
}

/**
 * Roles, as `role` or `role@space`, the membership provider must answer for [principal] in [tenant]; [newlyGained]
 * those it does not hold now, null when no `--memberships` export said what it holds.
 */
class PrincipalRoles(
    val tenant: TenantId,
    val principal: String,
    val roles: List<String>,
    val newlyGained: List<String>?
)

/** A binding of [principal] to [policy] in [tenant]'s document, in any app and space. */
class PrincipalBinding(val tenant: TenantId, val principal: String, val policy: PolicyId, val id: String)

/** A binding of [role] to [policy] in [tenant]'s document, in any app and space. */
class RoleBinding(val tenant: TenantId, val role: RoleId, val policy: PolicyId, val id: String)

class ApiKeyMigrationPlan(
    val keys: List<ConvertedApiKey>,
    val memberships: List<PrincipalRoles>,
    val principalBindings: List<PrincipalBinding>,
    val roleBindings: List<RoleBinding>,
    val rejections: List<String>,
) {
    val accepted: Boolean get() = rejections.isEmpty()

    /** This plan, also refusing what [more] says. */
    fun withRejections(more: List<String>): ApiKeyMigrationPlan =
        if (more.isEmpty()) {
            this
        } else {
            ApiKeyMigrationPlan(
                keys,
                memberships,
                principalBindings,
                roleBindings,
                more + rejections
            )
        }

    /** The change report, `api-keys-report.json`. */
    fun report(dryRun: Boolean, alreadyConverted: List<String>, changed: List<String> = emptyList()): JsonNode {
        val node = CoSecJson.mapper.createObjectNode()
        node.put("accepted", accepted)
        node.put("dryRun", dryRun)
        node.putArray("rejections").apply { rejections.forEach { add(it) } }
        node.putArray("keys").apply { keys.forEach { keyReport(addObject(), it) } }
        node.putArray("alreadyConverted").apply { alreadyConverted.forEach { add(it) } }
        node.putArray("changedSinceRead").apply { changed.forEach { add(it) } }
        node.putArray("memberships").apply { memberships.forEach { membershipReport(addObject(), it) } }
        bindingsReport(node)
        return node
    }

    private fun bindingsReport(node: ObjectNode) {
        node.putArray("principalBindings").apply {
            principalBindings.forEach {
                binding(addObject(), it.tenant, it.id, it.policy).putObject("subject")
                    .put("principal", it.principal).put("tenant", it.tenant.value)
            }
        }
        node.putArray("roleBindings").apply {
            roleBindings.forEach {
                binding(addObject(), it.tenant, it.id, it.policy).putObject("subject").put("role", it.role.value)
            }
        }
    }

    private fun membershipReport(node: ObjectNode, principal: PrincipalRoles) {
        node.put("tenant", principal.tenant.value).put("principal", principal.principal)
        node.putArray("roles").apply { principal.roles.forEach { add(it) } }
        principal.newlyGained?.let { gained -> node.putArray("newlyGained").apply { gained.forEach { add(it) } } }
    }

    private fun keyReport(node: ObjectNode, converted: ConvertedApiKey) {
        node.put("id", converted.key.id)
        node.put("tenant", converted.key.tenantId)
        node.put("mode", converted.mode.name.lowercase().replace('_', '-'))
        node.put("principal", converted.legacy.principalId)
        node.put("newPrincipal", converted.key.principalId)
        val cap = converted.key.roles
        if (cap == null) node.putNull("cap") else node.putArray("cap").apply { cap.forEach { add(it) } }
        node.put("defaultTenant", converted.legacy.tenantId.isNullOrEmpty())
        node.putArray("lost").apply { converted.lost.forEach { add(it) } }
        node.putArray("changes").apply { converted.changes.forEach { add(it) } }
    }

    private fun binding(node: ObjectNode, tenant: TenantId, id: String, policy: PolicyId): ObjectNode {
        node.put("tenant", tenant.value).put("id", id).put("app", "*").put("space", "*").put("policy", policy.value)
        return node
    }
}

/** [PublishedDocuments] as the heads in [store] are, each document read once. A document that cannot be read fails. */
class StoredDocuments(private val store: ControlStore) : PublishedDocuments {
    private val platform: PolicyDocument? by lazy { read(SnapshotKey.Platform) }
    private val tenants = HashMap<TenantId, PolicyDocument?>()
    private val all: List<PolicyDocument> by lazy {
        runBlocking { store.documents() }.mapNotNull { read(it) }
    }

    override fun attachable(tenant: TenantId, policy: PolicyId): Boolean {
        val own = tenant(tenant)?.policies?.firstOrNull { it.id == policy }
        val shared = platform?.policies?.firstOrNull { it.id == policy }
        if (own != null && shared != null &&
            PolicyDocumentCodec.policyTree(own) != PolicyDocumentCodec.policyTree(shared)
        ) {
            return false
        }
        return (own ?: shared)?.attachable == true
    }

    override fun principalGrants(tenant: TenantId, principal: String): List<String> =
        listOfNotNull(platform, tenant(tenant)).flatMap { document ->
            document.bindings.filter { it.names(tenant, principal) }.map { "${describe(document)}/${it.id.value}" }
        }

    override fun boundRole(role: RoleId): Boolean =
        all.any { document -> document.bindings.any { (it.subject as? SubjectRef.Role)?.id == role } }

    private fun Binding.names(tenant: TenantId, principal: String): Boolean =
        (subject as? SubjectRef.Principal)?.let { it.tenant == tenant && it.id.value == principal } == true

    private fun describe(document: PolicyDocument): String =
        if (document.kind == DocumentKind.PLATFORM) "platform" else "${document.kind.name.lowercase()}:${document.owner}"

    private fun tenant(tenant: TenantId): PolicyDocument? = tenants.getOrPut(tenant) {
        read(SnapshotKey.Tenant(tenant.value))
    }

    private fun read(key: SnapshotKey): PolicyDocument? {
        val stored = runBlocking { store.head(key) } ?: return null
        val (kind, owner) = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
        }
        return PolicyDocumentCodec.decode(kind, owner, stored.document.bodyBytes())
    }
}

/** The keys stored under a prefix: before RFC 0009, already converted (by hash), or not keys at all. */
class StoredApiKeys(val legacy: List<LegacyApiKey>, val converted: List<String>, val unreadable: List<String>) {
    companion object {
        fun read(
            commands: RedisCommands<String, String>,
            prefix: String,
            scan: (String) -> Sequence<String>
        ): StoredApiKeys {
            val legacy = ArrayList<LegacyApiKey>()
            val converted = ArrayList<String>()
            val unreadable = ArrayList<String>()
            scan("$prefix:*").sorted().forEach { redisKey ->
                val json = commands.get(redisKey) ?: return@forEach
                val hash = redisKey.removePrefix("$prefix:")
                try {
                    if (LegacyApiKey.isConverted(json)) converted += hash else legacy += LegacyApiKey.parse(hash, json)
                } catch (error: IllegalArgumentException) {
                    unreadable += "[$redisKey] is not an API key: ${error.message}"
                }
            }
            return StoredApiKeys(legacy, converted, unreadable)
        }

        /**
         * Rewrites the key under [redisKey] to [json] only while it still holds [expected], what was read (review M2): a
         * key revoked, disabled or changed since is left as it is. True when written.
         */
        fun replace(
            commands: RedisCommands<String, String>,
            redisKey: String,
            expected: String,
            json: String
        ): Boolean =
            commands.eval<Long>(COMPARE_AND_SET, io.lettuce.core.ScriptOutputType.INTEGER, arrayOf(redisKey), expected, json) == 1L

        private const val COMPARE_AND_SET =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2]) return 1 else return 0 end"
    }
}

/** `--memberships`: a JSON array of `{"tenant": ..., "principal": ..., "roles": ["role", "role@space", ...]}`. */
object MembershipsExport {
    fun read(json: ByteArray): Map<Pair<TenantId, String>, Set<String>> {
        val node = CoSecJson.mapper.readTree(json)
        require(node.isArray) { "--memberships is a JSON array of {tenant, principal, roles}." }
        return node.values().associate { entry ->
            require(entry.isObject && entry.propertyNames().toSet() == setOf("tenant", "principal", "roles")) {
                "--memberships entries have exactly tenant, principal and roles, not $entry"
            }
            val owner = TenantId(entry.get("tenant").asString()) to entry.get("principal").asString()
            owner to entry.get("roles").values().mapTo(LinkedHashSet()) { it.asString() }
        }
    }
}
