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
package me.ahoo.cosec.servlet

import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.tenant.Tenant
import javax.servlet.http.HttpServletRequest

/**
 * ServletRequestTenantIdParser .
 *
 * @author ahoo wang
 */
class ServletRequestTenantIdParser(private val tenantIdKey: String = RequestTenantIdParser.TENANT_ID_KEY) :
    RequestTenantIdParser<HttpServletRequest> {
    override fun parse(request: HttpServletRequest): String {
        val tenantId = request.getHeader(tenantIdKey)
        return if (tenantId.isNullOrEmpty()) {
            Tenant.DEFAULT_TENANT_ID
        } else {
            tenantId
        }
    }

    companion object {
        @JvmField
        val INSTANCE = ServletRequestTenantIdParser()
    }
}