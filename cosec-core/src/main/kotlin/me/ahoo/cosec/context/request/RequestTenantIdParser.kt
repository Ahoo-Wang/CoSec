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
package me.ahoo.cosec.context.request

import me.ahoo.cosec.tenant.Tenant

/**
 * Request Tenant Id Parser.
 *
 * @author ahoo wang
 */
fun interface RequestTenantIdParser<R> {
    fun parse(request: R): String

    companion object {
        const val TENANT_ID_KEY = "tenant_id"
    }
}

abstract class AbstractRequestTenantIdParser<R> : RequestTenantIdParser<R> {
    override fun parse(request: R): String {
        val tenantId = parseTenantId(request)
        return if (tenantId.isNullOrEmpty()) {
            Tenant.DEFAULT_TENANT_ID
        } else {
            tenantId
        }
    }

    abstract fun parseTenantId(request: R): String?
}
