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

package me.ahoo.cosec.spi.membership

import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Where the memberships of principals whose token carries [MembershipClaims.PROVIDER] come from (RFC 0004 §4.5.2):
 * memberships that change often, must be revoked at once, or do not fit in a token.
 *
 * The pipeline calls it through `me.ahoo.cosec.runtime.MembershipResolver` (`cosec-runtime`), which enforces the
 * contract: failure means deny. A failed or late answer makes the memberships unavailable, and the request fails closed
 * with 503; it never counts as "no memberships", because no roles also means no `inRole` DENY matches.
 *
 * A suspend function, like every data-plane SPI (RFC 0006 Q11).
 */
fun interface MembershipProvider {
    /**
     * Must not block the calling thread, which may be an event loop: an implementation that needs a blocking client
     * moves the call to its own bounded executor (RFC 0006 §7.7). Report a failed lookup by throwing. Answer
     * `Memberships(PROVIDER, emptyMap(), emptyList())` when the principal is unknown.
     */
    suspend fun memberships(tenant: TenantId, principal: PrincipalId): Memberships
}
