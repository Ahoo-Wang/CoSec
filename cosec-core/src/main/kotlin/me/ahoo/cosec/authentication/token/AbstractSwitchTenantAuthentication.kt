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
package me.ahoo.cosec.authentication.token

import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.TenantPrincipal
import reactor.core.publisher.Mono

/**
 * Abstract Switch Tenant Authentication .
 * Mainly used to switch tenant context.
 *
 * @author ahoo wang
 */
abstract class AbstractSwitchTenantAuthentication : Authentication<SwitchTenantCredentials, TenantPrincipal> {
    override val supportCredentials: Class<SwitchTenantCredentials>
        get() = SwitchTenantCredentials::class.java

    override fun authenticate(credentials: SwitchTenantCredentials): Mono<out TenantPrincipal> {
        return switchTenant(credentials.targetTenantId, credentials.principal)
    }

    /**
     * Causes the current user to switch to the target tenant context.
     *
     * @param targetTenantId target tenant id
     * @param previousPrincipal previous principal
     * @return new target tenant context principal
     */
    protected abstract fun switchTenant(
        targetTenantId: String,
        previousPrincipal: CoSecPrincipal
    ): Mono<out TenantPrincipal>
}

/**
 * Switch Tenant Credentials .
 *
 * @author ahoo wang
 */
interface SwitchTenantCredentials : Credentials {
    val targetTenantId: String
    val principal: CoSecPrincipal
}
