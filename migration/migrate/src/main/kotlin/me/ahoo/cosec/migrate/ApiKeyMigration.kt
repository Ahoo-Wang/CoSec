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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

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
 * In place and granted, a key is capped, so its principal's own bindings grant it nothing, though their DENYs still
 * apply to it (RC1-1); and no API key gets break-glass. Those are losses, never gains, and the report lists them per key.
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
    /** The app the converted keys are for (`--app`, RFC 0012 §3.5); null leaves them declaring none, as 7.x keys did. */
    private val app: String? = null,
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
        // 7.x never made key ids unique: two keys of one id would become one principal, or share one per-key role.
        val shared = keys.groupBy { it.tenant to it.id }.filterValues { it.size > 1 }.keys
        for (key in keys.sortedBy { it.id }) {
            val rejection = if (key.tenant to key.id in shared) {
                "another key of tenant [${key.tenant.value}] has the id [${key.id}]: give each key an id of its own"
            } else {
                rejection(key)
            }
            if (rejection != null) {
                rejections += "API key [${key.id}]: $rejection"
                continue
            }
            val conversion = when {
                granted(key) -> grant(key)
                staysInPlace(key) -> inPlace(key)
                else -> ownPrincipal(key) ?: run {
                    rejections += "API key [${key.id}]: the principal [$OWN_PRINCIPAL_PREFIX${key.id}] it would move to " +
                        "is already known (bound in a document or in --memberships): it would gain what that principal holds"
                    continue
                }
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
            // #1403: first, since everything after it reads the tenant's documents.
            !SnapshotKey.Tenant.isValidTenantId(key.tenant.value) ->
                "its tenant [${Identifiers.printable(key.tenant.value)}] is no 8.0 tenant id: rename it at its source"
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
            ApiKey(
                key.id,
                key.principalId,
                key.tenant.value,
                app,
                LinkedHashSet(key.roles),
                key.expiresAt,
                key.enabled
            ),
            ConvertedApiKey.Mode.IN_PLACE,
            lost(key),
        ),
    )

    /** The key on a principal of its own; null when that principal is already known, so it would not be the key's own. */
    private fun ownPrincipal(key: LegacyApiKey): Conversion? {
        val principal = OWN_PRINCIPAL_PREFIX + key.id
        if (documents.principalGrants(key.tenant, principal).isNotEmpty() || memberships?.containsKey(key.tenant to principal) == true) {
            return null
        }
        val bindings = key.policies.distinct().map { policy ->
            PrincipalBinding(key.tenant, principal, PolicyId(policy), "$principal:$policy")
        }
        return Conversion(
            ConvertedApiKey(
                key,
                // Even with no roles, the principal must be made known to the provider, or the key is refused.
                ApiKey(key.id, principal, key.tenant.value, app, null, key.expiresAt, key.enabled),
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
                ApiKey(key.id, key.principalId, key.tenant.value, app, cap, key.expiresAt, key.enabled),
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
