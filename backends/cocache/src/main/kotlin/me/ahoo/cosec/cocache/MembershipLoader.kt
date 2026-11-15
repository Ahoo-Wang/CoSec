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

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipAnswer

/**
 * Where the application's memberships come from: CoSec does not say where they are stored (RFC 0006 §5.5.2).
 *
 * Like every SPI of the pipeline, it must not block the calling thread: an implementation over a blocking client (JDBC,
 * say) moves the call to its own bounded executor (RFC 0006 §7.7). Answer [MembershipAnswer.Unknown] for a principal
 * you do not know, which refuses its credentials, and memberships with [Memberships.active] false for one that is
 * deactivated (RFC 0009 §4.2); report a failed lookup by throwing.
 */
@CoSecSpi
fun interface MembershipLoader {
    suspend fun load(tenant: TenantId, principal: PrincipalId): MembershipAnswer
}
