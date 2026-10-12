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

package me.ahoo.cosec.explain

import me.ahoo.cosec.authorization.BlacklistVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifySource
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext

/**
 * The rule this verification result points at; null when nothing matched.
 */
fun VerifyContext.toDecisionRef(): DecisionRef? = when (this) {
    is PolicyVerifyContext -> DecisionRef(
        layer = when (source) {
            PolicyVerifySource.GLOBAL -> ExplainLayer.GLOBAL_POLICY
            PolicyVerifySource.PRINCIPAL -> ExplainLayer.PRINCIPAL_POLICY
        },
        policyId = policy.id,
        statementIndex = statementIndex,
        statementName = statement.name,
    )

    is RoleVerifyContext -> DecisionRef(
        layer = ExplainLayer.ROLE_PERMISSION,
        roleId = roleId,
        permissionId = permission.id,
        statementName = permission.name,
    )

    RootVerifyContext -> DecisionRef(ExplainLayer.ROOT)
    BlacklistVerifyContext -> DecisionRef(ExplainLayer.BLACKLIST)
    else -> null
}
