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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId

/** An entry whose change needs `cosec:unprotect` (RFC 0007 §7.4.4). */
@InternalCoSecApi
sealed interface ProtectedEntry {
    @InternalCoSecApi
    data class Policy(val id: PolicyId) : ProtectedEntry

    @InternalCoSecApi
    data class Binding(val id: BindingId) : ProtectedEntry
}
