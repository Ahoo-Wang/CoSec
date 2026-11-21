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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ControlStatus.FORBIDDEN
import me.ahoo.cosec.control.change.ControlStatus.UNAVAILABLE
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * `GET /documents?kind=` (`CTL-68`): which tenant or app documents exist, from the store's list of the documents it
 * indexes, never by reading a document.
 * - A caller holding `cosec:read` on the layer ([ControlResources.layer]: `doc/tenant`, `doc/app`) sees every document
 *   of it, paged. That read grants no document's own read, and no document's read grants it.
 * - A tenant's principal asking for `tenant` sees its own tenant's document, if it may read it (one decision), and
 *   nothing else.
 * - Anyone else is refused as a route refuses a caller the policies do not permit: a 403 without detail, the same for
 *   every such caller, which tells nothing of which documents exist. There is no decision per listed document: a
 *   caller who may read a few documents knows their names already, and a scan of the whole list for them would be
 *   unbounded work on the control host.
 *
 * The page is cut after the filter, and a store that cannot answer is a 503.
 */
@InternalCoSecApi
class DocumentListing internal constructor(private val revisions: Revisions, private val authority: ChangeAuthority) {
    suspend fun list(operator: Operator, kind: DocumentKind, after: String?, limit: Int): DocumentPage {
        requireRequest(kind != DocumentKind.PLATFORM) { "A listing is of the tenant or the app documents." }
        requireRequest(limit in 1..ControlPlane.MAX_LIMIT) { "The limit is 1 to ${ControlPlane.MAX_LIMIT}, not $limit." }
        val shown: (SnapshotKey) -> Boolean = when (authority.decideLayer(operator, kind, ControlActions.READ)) {
            ControlDecision.PERMIT -> { key -> TenantIsolation.allows(operator, key) }
            ControlDecision.INDETERMINATE -> throw undecided(kind)
            ControlDecision.DENY -> {
                val own = ownDocument(operator, kind) ?: throw refused(kind)
                ({ key -> key == own })
            }
        }
        val listed = revisions.documents()
            .filter { ownerOf(it).first == kind && (after == null || it.toString() > after) && shown(it) }
            .sortedBy { it.toString() }
        val page = listed.take(limit)
        return DocumentPage(page, if (listed.size > limit) page.last().toString() else null)
    }

    /** A tenant principal's own tenant document, when it asks for tenants and may read it; null otherwise. */
    private suspend fun ownDocument(operator: Operator, kind: DocumentKind): SnapshotKey? {
        if (kind != DocumentKind.TENANT || operator.platform) return null
        val own = SnapshotKey.Tenant(operator.tenant.value)
        return when (authority.decide(operator, own, ControlActions.READ)) {
            ControlDecision.PERMIT -> own
            ControlDecision.DENY -> null
            ControlDecision.INDETERMINATE -> throw undecided(kind)
        }
    }

    private fun refused(kind: DocumentKind) =
        ChangeRejectedException(FORBIDDEN, "may not list the ${kind.name.lowercase()} documents")

    private fun undecided(kind: DocumentKind) = ChangeRejectedException(
        UNAVAILABLE,
        "whether the ${kind.name.lowercase()} documents may be listed could not be decided",
    )
}
