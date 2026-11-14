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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * A principal that published or approved a revision: its tenant and its id, which is all an actor is. A value that does
 * not grow (RFC 0010 C4): when delegation comes (RFC 0009 §7, RFC 8693 `act`), who acted on whose behalf is a value of its
 * own beside the actor, not a field of it.
 */
data class Actor(val tenant: TenantId, val principal: PrincipalId) {
    init {
        require(
            tenant.value.isNotEmpty() && principal.value.isNotEmpty()
        ) { "An actor names its tenant and principal." }
    }
}
