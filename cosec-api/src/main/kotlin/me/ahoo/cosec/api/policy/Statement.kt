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

/**
 * Policy statement that defines permission rules.
 *
 * A Statement is a single permission rule within a [Policy]. It contains:
 * - [name] - The identifier of this statement
 * - [effect] - Whether this statement allows or denies the action
 * - [action] - The action matcher that determines if this statement applies
 * - [condition] - The condition that must be met for this statement to apply
 *
 * Statements are evaluated in order, with DENY statements taking precedence
 * over ALLOW statements.
 *
 * @see Policy
 * @see Effect
 * @see ActionMatcher
 * @see ConditionMatcher
 */
interface Statement :
    Named,
    PermissionVerifier {
    override val name: String

    /** The effect of this statement - either ALLOW or DENY */
    val effect: Effect

    /** The action matcher that determines if this statement applies to a request */
    val action: ActionMatcher

    /** The condition that must be met for this statement to apply */
    val condition: ConditionMatcher

    /**
     * Verifies if the request matches this statement.
     *
     * @param request The incoming request
     * @param securityContext The security context of the request
     * @return [VerifyResult.ALLOW] if the action and condition match and effect is ALLOW,
     *         [VerifyResult.EXPLICIT_DENY] if the action and condition match and effect is DENY,
     *         [VerifyResult.IMPLICIT_DENY] otherwise
     */
    override fun verify(
        request: Request,
        securityContext: SecurityContext
    ): VerifyResult {
        if (!action.match(request = request, securityContext = securityContext)) {
            return VerifyResult.IMPLICIT_DENY
        }
        if (!condition.match(request = request, securityContext = securityContext)) {
            return VerifyResult.IMPLICIT_DENY
        }
        return when (effect) {
            Effect.ALLOW -> VerifyResult.ALLOW
            Effect.DENY -> VerifyResult.EXPLICIT_DENY
        }
    }
}
