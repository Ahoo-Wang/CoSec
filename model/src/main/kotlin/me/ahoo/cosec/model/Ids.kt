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

package me.ahoo.cosec.model

/**
 * The identifiers of the unified model (RFC 0004 §4.1). They appear on API boundaries only: compiled structures keep
 * plain strings, because collections of value classes box every element.
 */
@JvmInline
value class TenantId(val value: String) {
    companion object {
        /** Same as `Tenant.PLATFORM_TENANT_ID`. */
        val PLATFORM = TenantId("(platform)")

        /** Same as `CoSec.DEFAULT`: the tenant of anonymous principals. */
        val DEFAULT = TenantId("(0)")
    }
}

@JvmInline
value class PrincipalId(val value: String)

/** Never [Match.WILDCARD]: documents write `*` for [Match.Any], so a literal `*` app would decode as every app. */
@JvmInline
value class AppId(val value: String) {
    init {
        require(value != Match.WILDCARD) { "App id must not be the wildcard [${Match.WILDCARD}]." }
    }
}

/** Never [Match.WILDCARD], for the same reason as [AppId]. */
@JvmInline
value class SpaceId(val value: String) {
    init {
        require(value != Match.WILDCARD) { "Space id must not be the wildcard [${Match.WILDCARD}]." }
    }

    companion object {
        /** Same as `SpaceIdCapable.DEFAULT`. */
        val DEFAULT = SpaceId("")
    }
}

@JvmInline
value class RoleId(val value: String)

@JvmInline
value class PolicyId(val value: String)

@JvmInline
value class BindingId(val value: String)
