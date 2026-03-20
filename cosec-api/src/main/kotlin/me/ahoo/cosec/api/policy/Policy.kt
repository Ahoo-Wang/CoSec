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

package me.ahoo.cosec.api.policy

import me.ahoo.cosec.api.Named
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.tenant.Tenant

/** Type alias for policy identifier */
typealias PolicyId = String

/**
 * Permission Policy.
 *
 * A Policy is a collection of statements that define access control rules.
 * Each policy has:
 * - [id] - Unique identifier
 * - [name] - Human-readable name
 * - [category] - Category for grouping policies
 * - [description] - Detailed description
 * - [type] - Policy type (e.g., global, app-specific)
 * - [condition] - Condition that must be met for this policy to apply
 * - [statements] - List of permission statements
 *
 * Policies are evaluated to determine if a request should be allowed or denied.
 * When evaluating, the condition is checked first, then each statement is
 * evaluated in order (DENY statements take precedence).
 *
 * @see Statement
 * @see Effect
 * @see ConditionMatcher
 */
interface Policy :
    Named,
    Tenant,
    PermissionVerifier {
    /** Unique identifier for this policy */
    val id: PolicyId

    /** Category for grouping related policies (e.g., "admin", "user") */
    val category: String

    /** Detailed description of what this policy controls */
    val description: String

    /** Type of policy (e.g., GLOBAL, APP) */
    val type: PolicyType

    /** Condition that must be met for this policy to be considered */
    val condition: ConditionMatcher

    /** List of permission statements in this policy */
    val statements: List<Statement>

    /**
     * Verifies if the request is permitted by this policy.
     *
     * @param request The incoming request
     * @param securityContext The security context of the request
     * @return [VerifyResult.ALLOW] if policy condition and any ALLOW statement match,
     *         [VerifyResult.EXPLICIT_DENY] if any DENY statement matches,
     *         [VerifyResult.IMPLICIT_DENY] otherwise
     */
    override fun verify(
        request: Request,
        securityContext: SecurityContext
    ): VerifyResult {
        if (!condition.match(request, securityContext)) {
            return VerifyResult.IMPLICIT_DENY
        }
        statements
            .filter {
                it.effect == Effect.DENY
            }.forEach {
                val verifyResult = it.verify(request, securityContext)
                if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                    return VerifyResult.EXPLICIT_DENY
                }
            }

        statements
            .filter {
                it.effect == Effect.ALLOW
            }.forEach {
                val verifyResult = it.verify(request, securityContext)
                if (verifyResult == VerifyResult.ALLOW) {
                    return VerifyResult.ALLOW
                }
            }
        return VerifyResult.IMPLICIT_DENY
    }
}
