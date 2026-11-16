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
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId

/** The policies and bindings of one snapshot document, as the analysis reads them. */
@InternalCoSecApi
class GrantDocument(
    val policies: List<GrantPolicy> = emptyList(),
    val bindings: List<Binding> = emptyList(),
    /** The bindings whose change or removal needs `cosec:unprotect`. */
    val protectedBindings: Set<BindingId> = emptySet(),
) {
    private val byId: Map<PolicyId, GrantPolicy> = policies.associateBy { it.id }

    fun policy(id: PolicyId): GrantPolicy? = byId[id]

    @InternalCoSecApi
    companion object {
        val EMPTY: GrantDocument = GrantDocument()
    }
}
