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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId

/**
 * What the credential says about the principal. [tenant] is never blank: the adapter maps a credential without a tenant
 * to [TenantId.DEFAULT]. [app] is the app the credential declares (a token's `aud`, an API key's app, RFC 0012 §3.5),
 * null when it declares none.
 */
@InternalCoSecApi
class ScopeSubject(
    val tenant: TenantId,
    val authenticated: Boolean,
    val memberships: Memberships,
    val app: String? = null,
) {
    init {
        require(tenant.value.isNotBlank()) { "Tenant id must not be blank; use TenantId.DEFAULT." }
    }
}
