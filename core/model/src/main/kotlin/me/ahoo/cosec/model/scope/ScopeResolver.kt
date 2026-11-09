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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId

/**
 * What the credential says about the principal. [tenant] is never blank: the adapter maps a credential without a tenant
 * to [TenantId.DEFAULT]. [app] is the app the credential declares (a token's `aud`, an API key's app, RFC 0012 §3.5),
 * null when it declares none.
 */
class ScopeSubject(
    val tenant: TenantId,
    val authenticated: Boolean,
    val memberships: Memberships,
    val app: String? = null,
) {
    init {
        require(tenant.value.isNotBlank()) { "Tenant id must not be blank; use TenantId.DEFAULT." }
    }
}

/**
 * The app the host serves (RFC 0004 §4.6): on a gateway, the route's metadata `cosec-app-id`, else
 * `cosec.authorization.scope.app-id`; on any other host, that property (RC1-6). Null when neither names one: then the
 * credential's app applies (RFC 0012 §3.5).
 */
class HostScope(val app: AppId?) {
    init {
        require(app == null || ScopeResolver.isValidAppId(app.value)) {
            "Host app id [${Identifiers.printable(app?.value.orEmpty())}] is not valid."
        }
    }
}

sealed interface ScopeResolution {
    class Resolved(val scope: Scope) : ScopeResolution

    class Rejected(val reason: Reason) : ScopeResolution {
        enum class Reason { APP_MISMATCH, SPACE_NOT_MEMBER }
    }
}

/**
 * Resolves a request's scope from the credential, the host and the space the client selects (RFC 0004 §4.6). A pure
 * function, so it lives in `model/` next to the internal constructor of [Scope].
 *
 * - Tenant: the subject's. An anonymous subject is always in [TenantId.DEFAULT] and has no roles, whatever it is given.
 * - App (RFC 0012 §3.5): an authenticated subject's is its credential's. A host with an app ([HostScope]: a gateway
 *   route's, else the configured one) serves only credentials declaring that app; any other, or none, is
 *   [ScopeResolution.Rejected.Reason.APP_MISMATCH]. Without a host app the credential's app applies, and a credential
 *   declaring none is in no app, so no app document is read. An anonymous subject has no credential, so its app is the
 *   host's. A credential's app that cannot be an app id (see [isValidAppId]) is
 *   [ScopeResolution.Rejected.Reason.APP_MISMATCH] too, whether or not the host has an app. The client never declares
 *   an app.
 * - Space: an empty selection is [SpaceId.DEFAULT]. Any other space needs at least one role there, or it is
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

    /** The app of a request in none: no app document is read. */
    private val NO_APP = AppId("")

    /** Longer app ids are rejected rather than carried into document keys. */
    const val MAX_APP_ID_LENGTH = 128

    /**
     * Space ids beginning with it are CoSec's, as the tenants `(0)` and `(platform)`: the control plane writes the
     * default space as `(default)` (RC1-37), so no request selects one and no document binds in one.
     */
    const val RESERVED_SPACE_PREFIX = Identifiers.RESERVED_PREFIX

    /** Whether [space] is reserved for CoSec ([RESERVED_SPACE_PREFIX]). */
    fun isReservedSpaceId(space: String): Boolean = space.startsWith(RESERVED_SPACE_PREFIX)

    /**
     * Whether [app] can name an app: not blank, not [Match.WILDCARD], no `:` (it separates document key parts, as in
     * `SnapshotKey.App`), no control character (an app id reaches plain-text log lines, which it must not forge, RC1-15),
     * no unpaired UTF-16 surrogate (a Redis key holds `?` in its place, so the id would name another id's documents,
     * RC1-52, [Identifiers]) and at most [MAX_APP_ID_LENGTH] characters: the rule of a tenant id. One pass over the
     * characters: every request checks its app here, and the keys of its tenant's documents through the same rule.
     */
    fun isValidAppId(app: String): Boolean {
        if (app.isEmpty() || app.length > MAX_APP_ID_LENGTH || app == Match.WILDCARD) {
            return false
        }
        var blank = true
        for (index in app.indices) {
            val c = app[index]
            if (c == ':' || c.isISOControl() || Identifiers.isUnpairedSurrogateAt(app, index)) {
                return false
            }
            blank = blank && c.isWhitespace()
        }
        return !blank
    }

    /** [space] is what the client selects (`CoSec-Space-Id`), empty for none. */
    fun resolve(subject: ScopeSubject, space: String, host: HostScope): ScopeResolution {
        val credentialApp = subject.app
        if (credentialApp != null && !isValidAppId(credentialApp)) {
            return APP_MISMATCH
        }
        val hostApp = host.app
        if (subject.authenticated && hostApp != null && credentialApp != hostApp.value) {
            return APP_MISMATCH
        }
        // Neither the wildcard nor a reserved space is a space a principal can be a member of, whatever it claims.
        if (space == Match.WILDCARD || isReservedSpaceId(space)) {
            return SPACE_NOT_MEMBER
        }
        val app = hostApp ?: credentialApp?.takeIf { subject.authenticated }?.let(::AppId) ?: NO_APP
        val selected = SpaceId(space)
        val roles = if (subject.authenticated) subject.memberships.roles[selected].orEmpty() else emptySet()
        if (selected != SpaceId.DEFAULT && roles.isEmpty()) {
            return SPACE_NOT_MEMBER
        }
        val tenant = if (subject.authenticated) subject.tenant else TenantId.DEFAULT
        return ScopeResolution.Resolved(Scope(tenant, app, selected, roles.toList()))
    }
}
