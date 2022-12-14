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
package me.ahoo.cosec.api.tenant

import me.ahoo.cosec.api.CoSec

/**
 * Tenant for splitting customer boundaries horizontally.
 *
 * @author ahoo wang
 */
interface Tenant {
    /**
     * get Tenant ID.
     *
     * @return Tenant Id
     */
    val tenantId: String

    /**
     * 是否为根平台租户.
     *
     * @return If it returns true, the current Tenant is the root Tenant.
     */
    val isPlatformTenant: Boolean
        get() = isPlatform(tenantId)

    /**
     * 是否是默认租户
     */
    val isDefaultTenant: Boolean
        get() = isDefault(tenantId)

    /**
     * 是否是用户租户环境
     */
    val isUserTenant: Boolean
        get() = !isDefaultTenant && !isPlatformTenant

    companion object {
        const val TENANT_ID_KEY = "tenantId"

        /**
         * 根平台租户ID.
         */
        const val PLATFORM_TENANT_ID = "(platform)"
        const val DEFAULT_TENANT_ID = CoSec.DEFAULT

        @JvmStatic
        fun isPlatform(tenantId: String): Boolean {
            return PLATFORM_TENANT_ID == tenantId
        }

        @JvmStatic
        fun isDefault(tenantId: String): Boolean {
            return DEFAULT_TENANT_ID == tenantId
        }
    }
}
