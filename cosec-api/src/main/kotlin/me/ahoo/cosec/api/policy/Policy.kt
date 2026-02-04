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

typealias PolicyId = String

/**
 * Permission Policy
 */
interface Policy : Named, Tenant, PermissionVerifier {
    val id: PolicyId
    val category: String
    val description: String
    val type: PolicyType
    val condition: ConditionMatcher
    val statements: List<Statement>
    override fun verify(request: Request, securityContext: SecurityContext): VerifyResult {
        if (!condition.match(request, securityContext)) {
            return VerifyResult.IMPLICIT_DENY
        }
        statements.filter {
            it.effect == Effect.DENY
        }.forEach {
            val verifyResult = it.verify(request, securityContext)
            if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                return VerifyResult.EXPLICIT_DENY
            }
        }

        statements.filter {
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
