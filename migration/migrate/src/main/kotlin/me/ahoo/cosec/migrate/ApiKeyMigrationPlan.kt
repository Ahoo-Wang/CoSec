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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

class ApiKeyMigrationPlan(
    val keys: List<ConvertedApiKey>,
    val memberships: List<PrincipalRoles>,
    val principalBindings: List<PrincipalBinding>,
    val roleBindings: List<RoleBinding>,
    val rejections: List<String>,
) {
    val accepted: Boolean get() = rejections.isEmpty()

    companion object {
        /** A plan with a rejection: no key was written. */
        const val STATUS_REJECTED = "rejected"

        /** A dry run: no key was written. */
        const val STATUS_DRY_RUN = "dry-run"

        /** A run whose keys were rewritten. */
        const val STATUS_CONVERTED = "converted"
    }

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
    fun report(
        dryRun: Boolean,
        alreadyConverted: List<String>,
        changed: List<String> = emptyList(),
        status: String? = null,
    ): JsonNode {
        val node = CoSecJson.mapper.createObjectNode()
        node.put("accepted", accepted)
        node.put("dryRun", dryRun)
        // What the run did to the keys: `planned` is a plan whose keys were about to be rewritten (a report that stays so
        // after a crash still holds everything needed to finish); `converted` is one whose keys were.
        node.put(
            "status",
            status ?: if (!accepted) STATUS_REJECTED else if (dryRun) STATUS_DRY_RUN else STATUS_CONVERTED
        )
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
