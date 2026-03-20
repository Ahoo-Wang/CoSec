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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request

/**
 * Interface for verifying permissions.
 *
 * Implementations of this interface are responsible for determining whether
 * a request should be allowed or denied based on the policy rules.
 *
 * @see Policy
 * @see Statement
 */
fun interface PermissionVerifier {
    /**
     * Verifies if the request is permitted.
     *
     * @param request The incoming request
     * @param securityContext The security context of the request
     * @return The result of the verification
     */
    fun verify(
        request: Request,
        securityContext: SecurityContext
    ): VerifyResult
}

/**
 * Result of permission verification.
 *
 * This enum represents the possible outcomes of verifying a permission:
 * - [ALLOW]: The action is explicitly allowed
 * - [EXPLICIT_DENY]: The action is explicitly denied by a DENY statement
 * - [IMPLICIT_DENY]: The action is denied because no matching ALLOW statement exists
 *
 * @see PermissionVerifier
 * @see AuthorizeResult
 */
enum class VerifyResult {
    /** The action is explicitly allowed */
    ALLOW,

    /** The action is explicitly denied */
    EXPLICIT_DENY,

    /** The action is implicitly denied (no matching ALLOW statement) */
    IMPLICIT_DENY;

    /**
     * Converts this verification result to an authorization result.
     *
     * @return The corresponding [AuthorizeResult]
     */
    fun toAuthorizeResult(): AuthorizeResult =
        when (this) {
            ALLOW -> AuthorizeResult.ALLOW
            EXPLICIT_DENY -> AuthorizeResult.EXPLICIT_DENY
            IMPLICIT_DENY -> AuthorizeResult.IMPLICIT_DENY
        }
}
