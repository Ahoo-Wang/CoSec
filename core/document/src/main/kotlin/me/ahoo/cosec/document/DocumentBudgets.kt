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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.SubjectRef

/**
 * `CTL-40`, `CTL-41` (R1B-4): how many entries one document may hold, and how many operations one change may carry,
 * as the platform document's `limits` set them. The control plane enforces them on every change it checks; the
 * compiler and the hosts do not, so a stored document over a budget keeps being served.
 *
 * They bound what checking a change costs the control plane: a tenant administrator's change is analysed for
 * escalation, and that analysis grows with the square of the document's size (a change of 100,000 entries took over a
 * minute and exhausted a 512 MB heap before these budgets).
 */
@InternalCoSecApi
data class DocumentBudgets(
    /** Policies one document writes. */
    val maxPolicies: Int = DEFAULT_MAX_POLICIES,
    /** Statements of all the policies one document writes. */
    val maxStatements: Int = DEFAULT_MAX_STATEMENTS,
    /** Bindings to a role in one document. */
    val maxRoleBindings: Int = DEFAULT_MAX_ROLE_BINDINGS,
    /** Bindings to `Anyone` in one document: each is a candidate on every request in its scope. */
    val maxAnyoneBindings: Int = DEFAULT_MAX_ANYONE_BINDINGS,
    /** Operations of one change a client proposes (`CTL-41`). */
    val maxChangeOperations: Int = DEFAULT_MAX_CHANGE_OPERATIONS,
) {
    init {
        require(
            maxPolicies > 0 && maxStatements > 0 && maxRoleBindings > 0 && maxAnyoneBindings > 0 &&
                maxChangeOperations > 0
        ) { "A budget is a positive int: $this." }
    }

    /**
     * The entries one budget counts: [member] of `limits`, the JSON [path] of what it counts, and [what] in words.
     * Counted as the document grants them (DR1-B-1): the entries it writes and those its short forms derive
     * ([RoleExpansion]), without deriving them. A role's bindings are bindings to a role, and a catalog permission
     * with `action` is a policy of one statement: what checking a change and holding the document cost grows with them
     * as with the written ones. A document whose short forms are written out ([RoleExpansion.effective]) counts the same.
     */
    @InternalCoSecApi
    enum class Entry(val member: String, val path: String, val what: String) {
        POLICIES("maxPolicies", "$.policies", "policies (with one per catalog permission that has an action)"),
        STATEMENTS("maxStatements", "$.policies", "statements (with one per catalog permission that has an action)"),
        ROLE_BINDINGS("maxRoleBindings", "$.bindings", "bindings to a role (with the ones its roles derive)"),
        ANYONE_BINDINGS("maxAnyoneBindings", "$.bindings", "bindings to Anyone"),
        ;

        /** Its limit in [budgets]. */
        fun limit(budgets: DocumentBudgets): Int = when (this) {
            POLICIES -> budgets.maxPolicies
            STATEMENTS -> budgets.maxStatements
            ROLE_BINDINGS -> budgets.maxRoleBindings
            ANYONE_BINDINGS -> budgets.maxAnyoneBindings
        }

        /** How many of its entries [document] holds, the ones its short forms derive included. */
        fun count(document: PolicyDocument): Int = when (this) {
            POLICIES -> document.policies.size + derivedPolicies(document)
            STATEMENTS -> document.policies.sumOf { it.statements.size } + derivedPolicies(document)
            ROLE_BINDINGS -> saturated(
                document.bindings.count { it.subject is SubjectRef.Role } + RoleExpansion.grants(document)
            )
            ANYONE_BINDINGS -> document.bindings.count { it.subject == SubjectRef.Anyone }
        }

        /** The catalog permissions with `action`: one policy of one statement each ([RoleExpansion]). */
        private fun derivedPolicies(document: PolicyDocument): Int =
            document.catalog?.groups?.sumOf { group -> group.permissions.count { it.actions != null } } ?: 0

        private fun saturated(count: Long): Int = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @InternalCoSecApi
    companion object {
        const val DEFAULT_MAX_POLICIES: Int = 10_000
        const val DEFAULT_MAX_STATEMENTS: Int = 20_000
        const val DEFAULT_MAX_ROLE_BINDINGS: Int = 10_000
        const val DEFAULT_MAX_ANYONE_BINDINGS: Int = 1_000
        const val DEFAULT_MAX_CHANGE_OPERATIONS: Int = 20_000

        /** The budgets of a platform document that sets none. */
        val DEFAULT: DocumentBudgets = DocumentBudgets()
    }
}
