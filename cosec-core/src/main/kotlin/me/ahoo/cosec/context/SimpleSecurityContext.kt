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
package me.ahoo.cosec.context

import com.google.errorprone.annotations.ThreadSafe
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple implementation of [SecurityContext].
 *
 * This is a thread-safe implementation that holds the principal,
 * tenant information, and custom attributes for a request.
 *
 * @param principal The authenticated principal
 * @param tenant The tenant context (defaults to principal's tenant)
 * @param attributes Custom attributes for this context
 *
 * @see SecurityContext
 * @see CoSecPrincipal
 */
@ThreadSafe
class SimpleSecurityContext(
    override val principal: CoSecPrincipal,
    override val tenant: Tenant = principal.tenant,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : SecurityContext {
    companion object {
        /**
         * Creates an anonymous security context for unauthenticated requests.
         */
        fun anonymous(): SecurityContext = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS)
    }

    override fun toString(): String {
        if (principal.anonymous) {
            return "Context([Anonymous])"
        }
        if (tenant.isDefaultTenant) {
            return "Context([${principal.id}])"
        }
        return "Context([${principal.id}]@[${tenant.tenantId}])"
    }
}

/**
 * Extension property to get tenant from a principal.
 *
 * If the principal implements [TenantCapable], returns its tenant.
 * Otherwise, returns [SimpleTenant.DEFAULT].
 */
val CoSecPrincipal.tenant: Tenant
    get() {
        return if (this is TenantCapable) {
            this.tenant
        } else {
            SimpleTenant.DEFAULT
        }
    }
