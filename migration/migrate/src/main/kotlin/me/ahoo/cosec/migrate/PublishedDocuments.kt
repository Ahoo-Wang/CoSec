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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.TenantId

/** What the published documents say, as the engine reads them, for the plan. */
interface PublishedDocuments {
    /**
     * Whether [policy] attached in [tenant] resolves exactly as the engine resolves an attachment (`CandidateOrder`):
     * the tenant's own policy or the platform's, attachable, and when both define it, with the same content. Anything
     * else was never a grant: the engine answers indeterminate for it (RFC 0004 §6.4).
     */
    fun attachable(tenant: TenantId, policy: PolicyId): Boolean

    /** The ids of the principal and break-glass bindings that name [principal] in [tenant], in any document. */
    fun principalGrants(tenant: TenantId, principal: String): List<String>

    /** Whether any document binds [role]: a per-key role must not take an existing role's name. */
    fun boundRole(role: RoleId): Boolean
}
