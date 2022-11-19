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

import me.ahoo.cosec.principal.CoSecPrincipal
import me.ahoo.cosec.principal.TenantPrincipal
import me.ahoo.cosec.tenant.Tenant
import me.ahoo.cosec.tenant.TenantCapable
import me.ahoo.cosec.util.Internals.format
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe

/**
 * Security Context.
 *
 * @author ahoo wang
 */
@ThreadSafe
class SecurityContext(
    val principal: CoSecPrincipal,
    override val tenant: Tenant = principal.tenant
) : TenantCapable {
    companion object {
        val ANONYMOUS: SecurityContext = SecurityContext(TenantPrincipal.ANONYMOUS)
        val KEY = format("COSEC_SECURITY_CONTEXT")
    }

    private val attributes: MutableMap<String, Any> = ConcurrentHashMap()

    fun setAttribute(key: String, value: Any): SecurityContext {
        attributes[key] = value
        return this
    }

    fun <T> getAttribute(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as T?
    }

    fun <T> getRequiredAttribute(key: String): T {
        val value: T? = getAttribute(key)
        return requireNotNull(value = value) { "The required attribute:$key is not found." }
    }
}

val CoSecPrincipal.tenant: Tenant
    get() {
        return if (this is TenantCapable) {
            this.tenant
        } else {
            Tenant.DEFAULT
        }
    }
