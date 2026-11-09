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

package me.ahoo.cosec.model

import me.ahoo.cosec.annotations.InternalCoSecApi

/** Who a binding applies to (RFC 0004 §4.2). There are no groups: an identity provider's groups arrive as roles. */
@InternalCoSecApi
sealed interface SubjectRef {
    /** Principal ids are unique only within a tenant (D5), so a principal reference always names its tenant. */
    @InternalCoSecApi
    data class Principal(val tenant: TenantId, val id: PrincipalId) : SubjectRef

    /**
     * [tenant] is null for a platform-layer binding: it applies to a role of this name in any tenant
     * (RFC 0004 §5.2). In a tenant document it is the document's tenant.
     */
    @InternalCoSecApi
    data class Role(val tenant: TenantId?, val id: RoleId) : SubjectRef

    /** Any principal, anonymous ones included. */
    @InternalCoSecApi
    data object Anyone : SubjectRef
}

/**
 * Whether a binding of this subject can apply to a request decided in [tenant], as the engine looks it up: a principal
 * binding only in its principal's tenant, a role binding that names a tenant only there, a role binding without one and
 * [SubjectRef.Anyone] in every tenant.
 */
@InternalCoSecApi
fun SubjectRef.appliesIn(tenant: TenantId): Boolean = when (this) {
    SubjectRef.Anyone -> true
    is SubjectRef.Principal -> this.tenant == tenant
    is SubjectRef.Role -> this.tenant == null || this.tenant == tenant
}

/** Where a binding applies. Every dimension is explicit; there is no implied default (RFC 0004 §3 I3). */
@InternalCoSecApi
class BindingScope(val app: Match<AppId>, val space: Match<SpaceId>)

@InternalCoSecApi
sealed interface Match<out T> {
    @InternalCoSecApi
    data object Any : Match<Nothing>

    /** [value] is never [WILDCARD]; [AppId] and [SpaceId] already refuse it. */
    @InternalCoSecApi
    data class Exactly<T>(val value: T) : Match<T> {
        init {
            require(value != WILDCARD) { "Match.Exactly must not hold the wildcard [$WILDCARD]; use Match.Any." }
        }
    }

    @InternalCoSecApi
    companion object {
        /** How documents write [Any]. Reserved: no app or space id may be this. */
        const val WILDCARD = "*"
    }
}

/** Exact, case-sensitive equality; [Match.Any] matches every value. */
@InternalCoSecApi
fun <T> Match<T>.matches(value: T): Boolean = when (this) {
    Match.Any -> true
    is Match.Exactly -> this.value == value
}

/** Same as the generic [matches], without boxing [app]. */
@InternalCoSecApi
fun Match<AppId>.matches(app: AppId): Boolean = when (this) {
    Match.Any -> true
    is Match.Exactly -> value == app
}

/** Same as the generic [matches], without boxing [space]. */
@InternalCoSecApi
fun Match<SpaceId>.matches(space: SpaceId): Boolean = when (this) {
    Match.Any -> true
    is Match.Exactly -> value == space
}

/**
 * "[subject] is subject to [policy] in [scope]". The policy's statements decide; a binding has no effect of its own.
 */
@InternalCoSecApi
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
