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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId

/** How a policy came to apply to a request. */
sealed interface BindingRef {
    /** Through binding [id] of a [document]. */
    data class Bound(val id: BindingId, val document: DocumentKind) : BindingRef

    /** Attached by the principal's memberships (RFC 0004 §4.4). */
    data class Attached(val policy: PolicyId) : BindingRef

    /** Part of every snapshot: the blacklist rule (RFC 0004 §4.7). */
    data object Builtin : BindingRef

    /** A statement of the platform boundary that applies to [tenant] (RFC 0007 §7.6), as the trace names it. */
    data class Boundary(val tenant: TenantId) : BindingRef
}
