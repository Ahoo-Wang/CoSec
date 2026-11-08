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

package me.ahoo.cosec.runtime.token

import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.membership.MembershipClaims

/**
 * The text rule of ids, for the ids a verified token claims (RC1-52, `IDN-10`): its subject, its tenant, its `jti`, its
 * `roles` and its `policies`, none with an unpaired UTF-16 surrogate. A JSON token can carry one (`"\uD800"`); a Redis key
 * would hold `?` in its place, so the token would read another principal's memberships and revocations, or another
 * tenant's documents. An empty tenant is no tenant id either (`IDN-11`), whichever verifier read it: the default tenant
 * is for a token with no tenant claim. Such a token is invalid, on every path that verifies one: the pipeline's and
 * refresh's.
 */
internal object TokenClaims {
    fun areWellFormed(principal: TokenPrincipal): Boolean {
        val tenant = MembershipClaims.tenantOf(principal).value
        return tenant.isNotEmpty() &&
            Identifiers.isWellFormed(tenant) &&
            Identifiers.isWellFormed(principal.id) &&
            Identifiers.isWellFormed(principal.tokenId) &&
            Identifiers.areWellFormed(principal.roles) &&
            Identifiers.areWellFormed(principal.policies)
    }
}
