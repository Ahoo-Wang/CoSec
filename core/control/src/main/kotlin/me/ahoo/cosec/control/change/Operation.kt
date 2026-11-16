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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.Catalog
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionLimits

/**
 * An entry-level operation of a change (RFC 0007 §10.1). [ifMatch], where an operation has one, is the
 * [EntryDigest] of the entry as the author last saw it: the operation applies only if the entry is still that, so a
 * replay on a newer head (a rebase) fails instead of overwriting what someone else changed.
 */
@ExperimentalCoSecApi
sealed interface Operation {
    /** Adds the policy, or replaces the one of the same id. */
    @InternalCoSecApi
    class PutPolicy(val policy: PolicyDefinition, val ifMatch: String? = null) : Operation

    class RemovePolicy(val id: PolicyId, val ifMatch: String? = null) : Operation

    /** Adds the binding, or replaces the one of the same id. */
    @InternalCoSecApi
    class PutBinding(val binding: Binding, val ifMatch: String? = null) : Operation

    class RemoveBinding(val id: BindingId, val ifMatch: String? = null) : Operation

    /** Adds the rate limit rule (RFC 0006 §8.1), or replaces the one of the same id. */
    @InternalCoSecApi
    class PutRateLimit(val rule: RateLimitSource, val ifMatch: String? = null) : Operation

    class RemoveRateLimit(val id: String, val ifMatch: String? = null) : Operation

    /** Adds the role (RFC 0012 §7.1), or replaces the one of the same id as a whole. */
    @InternalCoSecApi
    class PutRole(val role: RoleDefinition, val ifMatch: String? = null) : Operation

    class RemoveRole(val id: RoleId, val ifMatch: String? = null) : Operation

    /** An app document's catalog; `null` removes it. */
    @InternalCoSecApi
    class PutCatalog(val catalog: Catalog?) : Operation

    /** The platform document's condition limits (RFC 0007 §3.1 N3); `null` returns to the defaults. */
    @InternalCoSecApi
    class SetLimits(val limits: ConditionLimits?) : Operation

    /** The external attributes the platform document declares (RFC 0007 §3.1 N3); `null` removes the section. */
    @InternalCoSecApi
    class SetAttributes(val attributes: List<Attribute>?) : Operation

    /**
     * The platform boundary (RFC 0007 §7.6): in the platform document the default, in a tenant document the one that
     * holds that tenant instead; `null` removes it.
     */
    @InternalCoSecApi
    class SetBoundary(val boundary: PolicyId?) : Operation
}
