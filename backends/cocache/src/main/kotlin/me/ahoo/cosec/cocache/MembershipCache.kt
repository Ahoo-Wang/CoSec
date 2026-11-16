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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Cache
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Memberships Cache (RFC 0006 §5.5.2): the memberships a [MembershipLoader] answered, keyed by [key], read by
 * [CoCacheMembershipProvider] with the loader on each call (an externally sourced CoCache `Cache`). Not the store of
 * record: entries expire, but a failed read throws (see [CoSecCacheSpecs.membership]).
 */
@InternalCoSecApi
interface MembershipCache : Cache<String, Memberships> {
    @InternalCoSecApi
    companion object {
        /** The same unambiguous subject key as [RevokedSubjectCache.key]. */
        fun key(tenant: TenantId, principal: PrincipalId): String = RevokedSubjectCache.key(tenant, principal)
    }
}
