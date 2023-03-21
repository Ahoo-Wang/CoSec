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

package me.ahoo.cosec.permission

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppPermissionEvaluator
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimpleTenantPrincipal

object DefaultAppPermissionEvaluator : AppPermissionEvaluator {
    override fun evaluate(appPermission: AppPermission) {
        val evaluateRequest = EvaluateRequest()
        val mockContext = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS)
        appPermission.condition.match(evaluateRequest, mockContext)
        appPermission.permissionIndexer.values.forEach { permission ->
            permission.verify(evaluateRequest, mockContext)

            permission.actions.forEach {
                it.match(evaluateRequest, mockContext)
            }

            permission.condition.match(evaluateRequest, mockContext)
        }
    }
}
