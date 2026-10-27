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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.Scope

/**
 * The composed snapshot one evaluation reads (RFC 0004 §7.3): three installed, immutable documents, fixed when the view
 * is built, plus what this request could not read. Building one copies nothing.
 *
 * A document that does not exist is an empty [CompiledDocument], not null: null means it was not loaded. The engine
 * fails closed on a document it needs and was not given: [tenant] always, [app] when the scope has roles and an app
 * (§7.4). It then reports [Cause.NotLoaded] unless a DENY matched, so a blocked request needs only [platform].
 *
 * [unresolved] holds what the caller could not read, such as [Cause.SourceUnavailable]: the evaluation is
 * indeterminate unless a DENY matches. It is used as given, not copied.
 */
class SnapshotView(
    val platform: CompiledDocument,
    val app: CompiledDocument?,
    val tenant: CompiledDocument?,
    val unresolved: List<Cause> = emptyList(),
    /** The revisions of the documents, for decision events (RFC 0004 §7.2); null when the caller does not know them. */
    val version: ViewVersion? = null,
) {
    init {
        require(platform.kind == DocumentKind.PLATFORM) { "Not a platform document: ${platform.kind}" }
        require(app == null || app.kind == DocumentKind.APP) { "Not an app document: ${app?.kind}" }
        require(tenant == null || tenant.kind == DocumentKind.TENANT) { "Not a tenant document: ${tenant?.kind}" }
    }

    /** Whether a policy of these documents reads an external attribute: only then is there anything to plan. */
    internal val readsExternal: Boolean =
        platform.readsExternal || app?.readsExternal == true || tenant?.readsExternal == true

    /**
     * A principal reads only its own tenant's document, and the app document of its own app. A request without an app
     * reads none: its role bindings would otherwise depend on whether the caller happened to load one.
     */
    internal fun checkScope(scope: Scope) {
        require(tenant == null || tenant.owner == scope.tenant.value) {
            "The tenant document of ${tenant?.owner} cannot decide for tenant ${scope.tenant.value}"
        }
        require(app == null || (scope.app.value.isNotEmpty() && app.owner == scope.app.value)) {
            "The app document of ${app?.owner} cannot decide for app '${scope.app.value}'"
        }
    }

    /**
     * The boundary that holds the request's tenant (RFC 0007 §7.6): its tenant document's, else the platform's
     * default; none for the platform tenant. Two field reads when there is none.
     */
    internal fun boundaryOf(scope: Scope): String? {
        if (scope.tenant == TenantId.PLATFORM) {
            return null
        }
        return tenant?.boundary ?: platform.boundary
    }

    /** The first document the request needs and was not given, or null. */
    internal fun notLoaded(scope: Scope): Cause.NotLoaded? =
        when {
            tenant == null -> NOT_LOADED_TENANT
            app == null && scope.roles.isNotEmpty() && scope.app.value.isNotEmpty() -> NOT_LOADED_APP
            else -> null
        }

    private companion object {
        val NOT_LOADED_TENANT = Cause.NotLoaded(DocumentKind.TENANT)
        val NOT_LOADED_APP = Cause.NotLoaded(DocumentKind.APP)
    }
}

/**
 * The revisions of the documents one view composes (RFC 0004 §7.2, RFC 0003 Q20): [app] and [tenant] are null when
 * the view has no such document, 0 for one that is not stored.
 */
data class ViewVersion(val platform: Long, val app: Long?, val tenant: Long?)
