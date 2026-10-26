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

package me.ahoo.cosec.model

/** Who a binding applies to (RFC 0004 §4.2). There are no groups: an identity provider's groups arrive as roles. */
sealed interface SubjectRef {
    /** Principal ids are unique only within a tenant (D5), so a principal reference always names its tenant. */
    data class Principal(val tenant: TenantId, val id: PrincipalId) : SubjectRef

    /**
     * [tenant] is null for a platform-layer binding: it applies to a role of this name in any tenant (7.x semantics,
     * RFC 0004 §5.2). In a tenant document it is the document's tenant.
     */
    data class Role(val tenant: TenantId?, val id: RoleId) : SubjectRef

    /** Any principal, anonymous ones included: the 7.x global policies. */
    data object Anyone : SubjectRef
}

/** Where a binding applies. Every dimension is explicit; there is no implied default (RFC 0004 §3 I3). */
class BindingScope(val app: Match<AppId>, val space: Match<SpaceId>)

sealed interface Match<out T> {
    data object Any : Match<Nothing>

    data class Exactly<T>(val value: T) : Match<T>
}

/**
 * "[subject] is subject to [policy] in [scope]". The policy's statements decide; a binding has no effect of its own.
 */
class Binding(
    val id: BindingId,
    val subject: SubjectRef,
    val scope: BindingScope,
    val policy: PolicyId,
    /**
     * When the binding expires, in epoch milliseconds. Required for break-glass bindings, optional otherwise. An expired
     * binding does not apply.
     */
    val expiresAt: Long? = null,
    val breakGlass: Boolean = false,
)
