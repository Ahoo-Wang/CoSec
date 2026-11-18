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

package me.ahoo.cosec.control.authz

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The control-plane resources (RFC 0007 §7.1): `doc/<layer>[/<id>]`, an entry `doc/<layer>[/<id>]/<kind>/<entryId>`
 * a section `doc/<layer>[/<id>]/<name>` (`catalog`, `limits`, `attributes`) and a scope
 * `scope/<tenant>/<app>/<space>`, a wildcard dimension written as the literal segment `*`. The default space, whose id
 * is empty, is written `(default)`: space ids beginning with `(` are CoSec's, as the tenants `(0)` and `(platform)`, so
 * no real space shares the stand-in (RC1-37; [me.ahoo.cosec.model.scope.ScopeResolver.RESERVED_SPACE_PREFIX]).
 */
@InternalCoSecApi
object ControlResources {
    private const val DOC = "doc"
    private const val SCOPE = "scope"
    const val DEFAULT_SPACE: String = "(default)"

    fun document(key: SnapshotKey): Resource = Resource(DOC, documentId(key), emptyMap())

    /** An entry, or with an empty [id] a section: `doc/app/order/catalog`, `doc/platform/limits`. */
    fun entry(key: SnapshotKey, kind: String, id: String): Resource =
        Resource(DOC, if (id.isEmpty()) "${documentId(key)}/$kind" else "${documentId(key)}/$kind/$id", emptyMap())

    /** [tenant] null: any tenant (a platform `Anyone` binding, an app role binding without a tenant). */
    fun scope(tenant: TenantId?, app: Match<AppId>, space: Match<SpaceId>): Resource {
        val spaceSegment = when (space) {
            Match.Any -> Match.WILDCARD
            is Match.Exactly -> space.value.value.ifEmpty { DEFAULT_SPACE }
        }
        val appSegment = when (app) {
            Match.Any -> Match.WILDCARD
            is Match.Exactly -> app.value.value
        }
        return Resource(
            SCOPE,
            "${segment(tenant?.value ?: Match.WILDCARD)}/${segment(appSegment)}/${segment(spaceSegment)}",
            emptyMap()
        )
    }

    private fun documentId(key: SnapshotKey): String = when (key) {
        SnapshotKey.Platform -> "platform"
        is SnapshotKey.App -> "app/${segment(key.appId)}"
        is SnapshotKey.Tenant -> "tenant/${segment(key.tenantId)}"
    }

    /**
     * An owner or scope part that would add a segment could make one document's resource look like another's. A valid
     * tenant or app id holds no `/` (#1403, `ScopeResolver.isValidAppId`), so for an owner this is a second line that a
     * valid id never reaches; space ids, which may hold one, still meet it first.
     */
    private fun segment(value: String): String {
        requireRequest(value.isNotEmpty() && '/' !in value) { "[$value] is not one resource segment." }
        return value
    }
}
