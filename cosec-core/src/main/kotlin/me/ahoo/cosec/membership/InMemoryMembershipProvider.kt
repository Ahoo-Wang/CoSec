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
import java.util.concurrent.ConcurrentHashMap

/**
 * A [MembershipProvider] that holds memberships in memory, for tests and local development. A change applies to the
 * next lookup.
 */
class InMemoryMembershipProvider : MembershipProvider {
    private val memberships = ConcurrentHashMap<Key, Memberships>()

    override fun memberships(tenant: TenantId, principal: PrincipalId): CompletableFuture<Memberships> =
        CompletableFuture.completedFuture(memberships[Key(tenant.value, principal.value)] ?: NONE)

    /** Sets the memberships of [principal] in [tenant]; their source must be [Memberships.Source.PROVIDER]. */
    fun put(tenant: TenantId, principal: PrincipalId, memberships: Memberships) {
        require(memberships.source == Memberships.Source.PROVIDER) {
            "Memberships from a provider must have the source PROVIDER, not ${memberships.source}."
        }
        this.memberships[Key(tenant.value, principal.value)] = memberships
    }

    /** Removes the memberships of [principal] in [tenant]: it then has none. */
    fun remove(tenant: TenantId, principal: PrincipalId) {
        memberships.remove(Key(tenant.value, principal.value))
    }

    private data class Key(val tenant: String, val principal: String)

    private companion object {
        val NONE = Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
    }
}
