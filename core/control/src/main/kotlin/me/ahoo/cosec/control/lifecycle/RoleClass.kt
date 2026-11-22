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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The role class (`CTL-79`, `cosec:manage-roles`): a change of a tenant's document that only writes or removes roles
 * needs no second person on account of who grants, when its author holds `cosec:manage-roles` on that document. What
 * such a change can grant is bounded by the format, not by the author: a tenant's role derives only bindings of its own
 * tenant's members, in its app, to the permission policies that app's document defines, which people approved there;
 * the hard rule keeps its members to their own tenant's document, and the tenant's boundary caps what it permits.
 *
 * It lifts only the doubts about who grants ([LIFTED]). An escalation that says the analysis could not tell (an app
 * document it could not read, a policy nothing defines, a budget spent) still waits, and so does a change that writes
 * anything but roles: it is analyzed whole. `cosec:write` and `cosec:grant` are decided for every change as before.
 *
 * A role of the control plane's own app (`cosec.control.app-id`) is never the class's, put or removed, whatever its
 * permissions: who governs a tenant's policies is not a business permission. A role that carried `cosec:approve`,
 * `cosec:write` and `cosec:grant` on the tenant's document would make two of its members the tenant's governors, who
 * then propose and approve between them what the class never would. A role of any other app permits no control call
 * at all, whatever its catalog names: a control request is decided in the control plane's app, where another app's
 * binding does not apply.
 */
internal object RoleClass {
    /** What the class lifts: the doubts about the author's own reach, never what the analysis could not tell. */
    val LIFTED: Set<Escalation.Reason> = setOf(
        Escalation.Reason.CROSS_TENANT,
        Escalation.Reason.NOT_COVERED,
        Escalation.Reason.DENY_OVERLAP,
    )

    /**
     * Whether [operations] of [key] are the class's: a tenant document's, and only roles written or removed, none of
     * [controlApp]. A removed role's app is the one [base] gives it; one [base] does not hold is not the class's.
     */
    fun covers(key: SnapshotKey, operations: List<Operation>, base: PolicyDocument?, controlApp: AppId): Boolean =
        key is SnapshotKey.Tenant && operations.isNotEmpty() && operations.all { operation ->
            when (operation) {
                is Operation.PutRole -> operation.role.app != controlApp
                is Operation.RemoveRole -> base?.roles?.firstOrNull { it.id == operation.id }?.app.let { it != null && it != controlApp }
                else -> false
            }
        }

    /**
     * [escalations] as the class leaves them: one record naming [author], the grant and what it lifted, in place of
     * escalations it lifts every one of, when it covers the change and [holds] says the author holds the grant; unchanged
     * otherwise. [holds] is asked only when the rest would lift something.
     */
    suspend fun apply(
        escalations: List<Escalation>,
        change: Proposed,
        author: Actor,
        holds: suspend () -> Boolean,
    ): List<Escalation> {
        val key = change.key
        val covered = covers(key, change.operations, change.base, change.controlApp)
        if (escalations.isEmpty() || !covered || escalations.any { it.reason !in LIFTED }) {
            return escalations
        }
        if (!holds()) {
            return escalations
        }
        val lifted = escalations.groupingBy {
            it.reason
        }.eachCount().entries.joinToString { (reason, count) -> "$reason x$count" }
        return listOf(
            Escalation(
                "roles of $key",
                Escalation.Reason.ROLE_CLASS,
                "${author.principal.value}@${author.tenant.value} holds cosec:manage-roles on $key; lifted: $lifted",
            ),
        )
    }

    /** What [apply] reads of a change: its document, its operations, the head they apply to, the control plane's app. */
    class Proposed(val key: SnapshotKey, val operations: List<Operation>, val base: PolicyDocument?, val controlApp: AppId)
}
