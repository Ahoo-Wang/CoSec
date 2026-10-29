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
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
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
 * and its tenant was optional.
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
) {
    companion object {
        private val FIELDS = setOf(
            "id",
            "principalId",
            "tenantId",
            "roles",
            "policies",
            "attributes",
            "expiresAt",
            "enabled"
        )

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

/**
 * Whether policy [PolicyId] could be attached to a principal of a tenant, as the engine resolves an attachment
 * (`CandidateOrder`): the tenant's own policy, else the platform's, and only when it is attachable.
 */
fun interface Attachability {
    fun of(tenant: TenantId, policy: PolicyId): Attachable

    enum class Attachable {
        YES,

        /** Missing or not attachable: the attachment never applied. */
        NO,

        /** Both the tenant and the platform define it: whether it applied depends on their content, so it is dropped. */
        AMBIGUOUS,
    }
}

/**
 * `cosec-migrate api-keys` (RFC 0009 §4.4): turns keys that granted roles and policies into keys that only narrow, and
 * says what the principals must hold for the keys to keep exactly what they had.
 *
 * - A key's roles go to its principal, as memberships the `MembershipProvider` must answer; several keys of one principal
 *   give it the union, and each key keeps its own roles as its cap, so no key gains another key's roles.
 * - A key's attached policies become a role of its own, `<role prefix><key id>`, held by the principal and bound to
 *   those policies in the key's tenant document, in the default space and in every space the key had a role in, where
 *   attachments applied; the key's cap names that role. An attachment the engine never applied (a missing or not
 *   attachable policy) is dropped, and an ambiguous one too, and reported.
 * - A key without a tenant goes to the default tenant, flagged.
 * - A key with attributes is refused: 8.0 keys carry none, and dropping them could stop a condition that reads them
 *   from denying. Nothing is converted until every key is accepted.
 *
 * So the cap of every key is its old roles and its own role: a key never gains access. It keeps all of it once the
 * memberships and the bindings are in place, which is why they are applied before the keys are converted.
 */
class ApiKeyMigration(
    private val rolePrefix: String = DEFAULT_ROLE_PREFIX,
    private val attachability: Attachability,
) {
    init {
        require(rolePrefix.isNotEmpty() && '@' !in rolePrefix) { "The role prefix is not empty and has no '@'." }
    }

    fun plan(keys: List<LegacyApiKey>): ApiKeyMigrationPlan {
        val converted = ArrayList<ConvertedApiKey>()
        val rejections = ArrayList<String>()
        val memberships = LinkedHashMap<Pair<TenantId, String>, LinkedHashSet<String>>()
        val bindings = ArrayList<RoleBinding>()
        for (key in keys.sortedBy { it.id }) {
            val rejection = rejection(key)
            if (rejection != null) {
                rejections += rejection
                continue
            }
            val conversion = convert(key)
            converted += conversion.key
            bindings += conversion.bindings
            memberships.getOrPut(TenantId(conversion.key.key.tenantId) to key.principalId) { LinkedHashSet() } +=
                conversion.key.key.roles.orEmpty()
        }
        return ApiKeyMigrationPlan(
            converted,
            memberships.map { (owner, roles) -> PrincipalRoles(owner.first, owner.second, roles.toList()) },
            bindings,
            rejections,
        )
    }

    private fun rejection(key: LegacyApiKey): String? {
        if (key.attributes.isNotEmpty()) {
            return "API key [${key.id}] has attributes ${key.attributes.keys}: 8.0 keys carry none, and a policy " +
                "condition may read them. Remove them once no condition does, then run again."
        }
        val malformed = MembershipClaims.parse(key.roles, emptyList()).malformed
        return if (malformed.isEmpty()) null else "API key [${key.id}] has malformed roles $malformed, which applied nowhere."
    }

    private class Conversion(val key: ConvertedApiKey, val bindings: List<RoleBinding>)

    /** [key] with its cap: its old roles, and its own role where its attachments applied. */
    private fun convert(key: LegacyApiKey): Conversion {
        val tenant = key.tenantId?.takeIf { it.isNotEmpty() }?.let(::TenantId) ?: TenantId.DEFAULT
        val applied = ArrayList<PolicyId>()
        val dropped = ArrayList<String>()
        key.policies.distinct().map(::PolicyId).forEach { policy ->
            when (attachability.of(tenant, policy)) {
                Attachability.Attachable.YES -> applied += policy
                Attachability.Attachable.NO -> dropped += "${policy.value} (missing or not attachable: it never applied)"
                Attachability.Attachable.AMBIGUOUS ->
                    dropped += "${policy.value} (defined by the tenant and the platform: check and bind it by hand)"
            }
        }
        val cap = LinkedHashSet(key.roles)
        val bindings = ArrayList<RoleBinding>()
        if (applied.isNotEmpty()) {
            val role = RoleId(rolePrefix + key.id)
            val spaces = MembershipClaims.parse(key.roles, emptyList()).roles.keys - SpaceId.DEFAULT
            cap += role.value
            spaces.forEach { space -> cap += "${role.value}@${space.value}" }
            applied.forEach { policy -> bindings += RoleBinding(tenant, role, policy, "${role.value}:${policy.value}") }
        }
        val converted = ConvertedApiKey(
            key.hash,
            ApiKey(key.id, key.principalId, tenant.value, cap, key.expiresAt, key.enabled),
            defaultTenant = key.tenantId.isNullOrEmpty(),
            dropped = dropped,
        )
        return Conversion(converted, bindings)
    }

    companion object {
        const val DEFAULT_ROLE_PREFIX: String = "api-key."
    }
}

class ConvertedApiKey(val hash: String, val key: ApiKey, val defaultTenant: Boolean, val dropped: List<String>)

/** Roles, as `role` or `role@space`, the membership provider must answer for [principal] in [tenant]. */
class PrincipalRoles(val tenant: TenantId, val principal: String, val roles: List<String>)

/** A binding of [role] to [policy] in [tenant]'s document, in any app and space. */
class RoleBinding(val tenant: TenantId, val role: RoleId, val policy: PolicyId, val id: String)

class ApiKeyMigrationPlan(
    val keys: List<ConvertedApiKey>,
    val memberships: List<PrincipalRoles>,
    val bindings: List<RoleBinding>,
    val rejections: List<String>,
) {
    val accepted: Boolean get() = rejections.isEmpty()

    /** This plan, also refusing what [more] says. */
    fun withRejections(more: List<String>): ApiKeyMigrationPlan =
        if (more.isEmpty()) this else ApiKeyMigrationPlan(keys, memberships, bindings, more + rejections)

    /** The change report, `api-keys-report.json`. */
    fun report(dryRun: Boolean, alreadyConverted: List<String>): JsonNode {
        val node = CoSecJson.mapper.createObjectNode()
        node.put("accepted", accepted)
        node.put("dryRun", dryRun)
        node.putArray("rejections").apply { rejections.forEach { add(it) } }
        node.putArray("keys").apply { keys.forEach { keyReport(addObject(), it) } }
        node.putArray("alreadyConverted").apply { alreadyConverted.forEach { add(it) } }
        node.putArray("memberships").apply {
            memberships.forEach { principal ->
                addObject().put("tenant", principal.tenant.value).put("principal", principal.principal)
                    .putArray("roles").apply { principal.roles.forEach { add(it) } }
            }
        }
        node.putArray("roleBindings").apply { bindings.forEach { bindingReport(addObject(), it) } }
        return node
    }

    private fun keyReport(node: ObjectNode, converted: ConvertedApiKey) {
        node.put("id", converted.key.id)
        node.put("tenant", converted.key.tenantId)
        node.put("principal", converted.key.principalId)
        node.putArray("cap").apply { converted.key.roles.orEmpty().forEach { add(it) } }
        node.put("defaultTenant", converted.defaultTenant)
        node.putArray("droppedAttachments").apply { converted.dropped.forEach { add(it) } }
    }

    private fun bindingReport(node: ObjectNode, binding: RoleBinding) {
        node.put("tenant", binding.tenant.value)
        node.put("id", binding.id)
        node.putObject("subject").put("role", binding.role.value)
        node.put("app", "*")
        node.put("space", "*")
        node.put("policy", binding.policy.value)
    }
}

/**
 * Attachability as the published documents in [store] define it, each document read once. A document that cannot be
 * read fails the run: the plan must not guess.
 */
class StoredAttachability(private val store: ControlStore) : Attachability {
    private val platform by lazy { policies(SnapshotKey.Platform) }
    private val tenants = HashMap<TenantId, Map<PolicyId, Boolean>>()

    override fun of(tenant: TenantId, policy: PolicyId): Attachability.Attachable {
        val own = tenants.getOrPut(tenant) { policies(SnapshotKey.Tenant(tenant.value)) }[policy]
        val shared = platform[policy]
        return when {
            own != null && shared != null -> Attachability.Attachable.AMBIGUOUS
            (own ?: shared) == true -> Attachability.Attachable.YES
            else -> Attachability.Attachable.NO
        }
    }

    /** Each policy of the document under [key] and whether it is attachable; none when nothing is published. */
    private fun policies(key: SnapshotKey): Map<PolicyId, Boolean> {
        val stored = runBlocking { store.head(key) } ?: return emptyMap()
        val owner = (key as? SnapshotKey.Tenant)?.tenantId ?: ""
        val kind = if (owner.isEmpty()) {
            DocumentKind.PLATFORM
        } else {
            DocumentKind.TENANT
        }
        val document = PolicyDocumentCodec.decode(kind, owner, stored.document.bodyBytes())
        return document.policies.associate { it.id to it.attachable }
    }
}

/** The keys stored under a prefix: before RFC 0009, already converted (by hash), or not keys at all. */
class StoredApiKeys(val legacy: List<LegacyApiKey>, val converted: List<String>, val unreadable: List<String>) {
    companion object {
        fun read(
            commands: RedisCommands<String, String>,
            prefix: String,
            scan: (String) -> Sequence<String>,
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
    }
}
