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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyEvaluator
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimpleTenantPrincipal

object DefaultPolicyEvaluator : PolicyEvaluator {
    private val mockRequest = object : Request {
        override val path: String
            get() = "/policies/test"
        override val method: String
            get() = "POST"
        override val remoteIp: String
            get() = "mockRemoteIp"
        override val origin: String
            get() = "mockOrigin"
        override val referer: String
            get() = "mockReferer"
        override val tenantId: String
            get() = Tenant.DEFAULT_TENANT_ID
    }
    private val mockContext = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS)

    override fun evaluate(policy: Policy) {
        policy.statements.forEach { statement ->
            statement.verify(mockRequest, mockContext)

            statement.actions.forEach {
                it.match(mockRequest, mockContext)
            }

            statement.conditions.forEach {
                it.match(mockRequest, mockContext)
            }
        }
    }
}
