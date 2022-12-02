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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request

interface Statement : PermissionVerifier {
    val effect: Effect
    val actions: Set<ActionMatcher>
    val conditions: Set<ConditionMatcher>

    override fun verify(request: Request, securityContext: SecurityContext): VerifyResult {
        conditions.all {
            it.match(request, securityContext)
        }.let { conditionMatched ->
            if (!conditionMatched) {
                return VerifyResult.IMPLICIT_DENY
            }
        }

        actions.any {
            it.match(request, securityContext)
        }.let { actionMatched ->
            if (!actionMatched) {
                return VerifyResult.IMPLICIT_DENY
            }
        }
        return when (effect) {
            Effect.ALLOW -> VerifyResult.ALLOW
            Effect.DENY -> VerifyResult.EXPLICIT_DENY
        }
    }
}