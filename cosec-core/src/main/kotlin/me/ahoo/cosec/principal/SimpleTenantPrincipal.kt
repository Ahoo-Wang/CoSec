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
package me.ahoo.cosec.principal

import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.tenant.SimpleTenant

/**
 * Simple Tenant Principal .
 *
 * @author ahoo wang
 */
data class SimpleTenantPrincipal(
    override val delegate: CoSecPrincipal,
    override val tenant: Tenant,
) : TenantPrincipal,
    CoSecPrincipal by delegate,
    Delegated<CoSecPrincipal> {
    companion object {
        @JvmField
        val ANONYMOUS: TenantPrincipal = SimpleTenantPrincipal(SimplePrincipal.ANONYMOUS, SimpleTenant.DEFAULT)
    }
}
