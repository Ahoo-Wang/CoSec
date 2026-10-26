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

package me.ahoo.cosec.membership

import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.util.concurrent.CompletableFuture

/**
 * Where the memberships of principals whose token carries [MembershipClaims.PROVIDER] come from (RFC 0004 §4.5.2):
 * memberships that change often, must be revoked at once, or do not fit in a token.
 *
 * Register it as a Spring bean. Callers go through [MembershipResolver], which enforces the contract: failure means
 * deny. A failed or late answer makes the memberships unavailable, and the request fails closed; it never counts as
 * "no memberships", because no roles also means no `inRole` DENY matches.
 */
fun interface MembershipProvider {
    /**
     * Never blocks and never throws: report a failed lookup by completing the future exceptionally. Complete with
     * `Memberships(PROVIDER, emptyMap(), emptyList())` when the principal is unknown.
     */
    fun memberships(tenant: TenantId, principal: PrincipalId): CompletableFuture<Memberships>
}
