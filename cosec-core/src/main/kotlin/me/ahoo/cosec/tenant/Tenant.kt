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
package me.ahoo.cosec.tenant

import me.ahoo.cosec.CoSec
import me.ahoo.cosec.internal.InternalIds.wrap

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
    val isPlatform: Boolean
        get() = PLATFORM_TENANT_ID == tenantId

    companion object {
        /**
         * 根平台租户ID.
         */
        @JvmField
        val PLATFORM_TENANT_ID = wrap("platform")

        @JvmField
        val PLATFORM: Tenant = SimpleTenant(PLATFORM_TENANT_ID)

        @JvmField
        val DEFAULT_TENANT_ID = CoSec.DEFAULT

        @JvmField
        val DEFAULT: Tenant = SimpleTenant(DEFAULT_TENANT_ID)
    }
}

data class SimpleTenant(override val tenantId: String) : Tenant
