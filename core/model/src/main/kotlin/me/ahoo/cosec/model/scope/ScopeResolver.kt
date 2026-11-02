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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId

/**
 * What the credential says about the principal. [tenant] is never blank: the adapter maps a credential without a tenant
 * to [TenantId.DEFAULT], as 7.x does.
 */
class ScopeSubject(val tenant: TenantId, val authenticated: Boolean, val memberships: Memberships) {
    init {
        require(tenant.value.isNotBlank()) { "Tenant id must not be blank; use TenantId.DEFAULT." }
    }
}

/** The scope the client asks for, from `CoSec-App-Id` and `CoSec-Space-Id`. Empty means not declared. */
class DeclaredScope(val app: String, val space: String)

/** The app the host is configured for; for the gateway, the route's. Null when the host does not say. */
class HostScope(val app: AppId?) {
    init {
        require(app == null || ScopeResolver.isValidAppId(app.value)) { "Host app id [${app?.value}] is not valid." }
    }
}

sealed interface ScopeResolution {
    class Resolved(val scope: Scope) : ScopeResolution

    class Rejected(val reason: Reason) : ScopeResolution {
        enum class Reason { APP_MISMATCH, SPACE_NOT_MEMBER }
    }
}

/**
 * Checks a declared scope against the credential and the host (RFC 0004 §4.6). A pure function, so it lives in
 * `model/` next to the internal constructor of [Scope].
 *
 * - Tenant: the subject's. An anonymous subject is always in [TenantId.DEFAULT] and has no roles, whatever it is given.
 * - App: the host's, when it has one; a declared app that differs is [ScopeResolution.Rejected.Reason.APP_MISMATCH].
 *   Otherwise the declared one, as in 7.x. A declared app that cannot be an app id (see [isValidAppId]) is
 *   [ScopeResolution.Rejected.Reason.APP_MISMATCH] too, whether or not the host has an app.
 * - Space: an empty declaration is [SpaceId.DEFAULT]. Any other space needs at least one role there, or it is
 *   [ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER]: falling back to the default space would let the backend act in a
 *   space the principal is not a member of. A declared [Match.WILDCARD] is never a member.
 *
 * Matching is exact: no trimming, no case folding.
 *
 * The app is checked before the space, so a request failing both is always rejected for the app.
 */
object ScopeResolver {
    private val APP_MISMATCH = ScopeResolution.Rejected(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    private val SPACE_NOT_MEMBER = ScopeResolution.Rejected(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)

    /** Longer declared app ids are rejected rather than carried into document keys. */
    const val MAX_APP_ID_LENGTH = 128

    /**
     * Space ids beginning with it are CoSec's, as the tenants `(0)` and `(platform)`: the control plane writes the
     * default space as `(default)` (RC1-37), so no request selects one and no document binds in one.
     */
    const val RESERVED_SPACE_PREFIX = "("

    /** Whether [space] is reserved for CoSec ([RESERVED_SPACE_PREFIX]). */
    fun isReservedSpaceId(space: String): Boolean = space.startsWith(RESERVED_SPACE_PREFIX)

    /**
     * Whether [app] can name an app: not blank, not [Match.WILDCARD], no `:` (it separates document key parts, as in
     * `SnapshotKey.App`) and at most [MAX_APP_ID_LENGTH] characters.
     */
    fun isValidAppId(app: String): Boolean =
        app.isNotBlank() && app != Match.WILDCARD && !app.contains(':') && app.length <= MAX_APP_ID_LENGTH

    fun resolve(subject: ScopeSubject, declared: DeclaredScope, host: HostScope): ScopeResolution {
        val hostApp = host.app
        if (declared.app.isNotEmpty() && !isValidAppId(declared.app)) {
            return APP_MISMATCH
        }
        if (hostApp != null && declared.app.isNotEmpty() && declared.app != hostApp.value) {
            return APP_MISMATCH
        }
        // Neither the wildcard nor a reserved space is a space a principal can be a member of, whatever it claims.
        if (declared.space == Match.WILDCARD || isReservedSpaceId(declared.space)) {
            return SPACE_NOT_MEMBER
        }
        val app = hostApp ?: AppId(declared.app)
        val appSource = if (hostApp != null) Scope.AppSource.HOST else Scope.AppSource.DECLARED
        val space = SpaceId(declared.space)
        val roles = if (subject.authenticated) subject.memberships.roles[space].orEmpty() else emptySet()
        if (space != SpaceId.DEFAULT && roles.isEmpty()) {
            return SPACE_NOT_MEMBER
        }
        val tenant = if (subject.authenticated) subject.tenant else TenantId.DEFAULT
        return ScopeResolution.Resolved(Scope(tenant, app, appSource, space, roles.toList()))
    }
}
