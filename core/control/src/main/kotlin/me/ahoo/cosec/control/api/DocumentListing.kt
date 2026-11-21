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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ControlStatus.UNAVAILABLE
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * `GET /documents?kind=` (`CTL-68`): which tenant or app documents exist, from the store's list of the documents it
 * indexes, never by reading a document. A document is listed only if the caller may see it: in a document its tenant may
 * reach, and with `cosec:read` on the list ([LIST]) or on that document. What the caller may not see is left out before
 * the page is cut, and nothing in the answer counts or names it (`CTL-55`'s rule). A store that cannot answer is a 503.
 */
@InternalCoSecApi
class DocumentListing internal constructor(private val revisions: Revisions, private val authority: ChangeAuthority) {
    suspend fun list(operator: Operator, kind: DocumentKind, after: String?, limit: Int): DocumentPage {
        requireRequest(kind != DocumentKind.PLATFORM) { "A listing is of the tenant or the app documents." }
        requireRequest(
            limit in 1..ControlPlane.MAX_LIMIT
        ) { "The limit is 1 to ${ControlPlane.MAX_LIMIT}, not $limit." }
        val candidates = revisions.documents()
            .filter { ownerOf(it).first == kind && (after == null || it.toString() > after) }
            .sortedBy { it.toString() }
        var everything: Boolean? = null
        val found = ArrayList<SnapshotKey>()
        for (key in candidates) {
            if (found.size > limit) break
            if (!TenantIsolation.allows(operator, key)) continue
            val all = everything ?: permitted(operator, LIST).also { everything = it }
            if (all || permitted(operator, key)) found += key
        }
        val page = found.take(limit)
        return DocumentPage(page, if (found.size > limit) page.last().toString() else null)
    }

    private suspend fun permitted(operator: Operator, key: SnapshotKey): Boolean =
        when (authority.decide(operator, key, ControlActions.READ)) {
            ControlDecision.PERMIT -> true
            ControlDecision.DENY -> false
            ControlDecision.INDETERMINATE ->
                throw ChangeRejectedException(UNAVAILABLE, "whether [$key] may be read could not be decided")
        }

    private companion object {
        /** What `cosec:read` on lets a caller list every document (design §0.8): the platform document. */
        val LIST: SnapshotKey = SnapshotKey.Platform
    }
}
