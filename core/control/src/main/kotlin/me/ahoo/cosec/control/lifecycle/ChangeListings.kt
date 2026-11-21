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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ControlStatus.UNAVAILABLE
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * `GET /changes` (R2C-4, `CTL-55`): the open changes of one document, or in one state, a page at a time. A change is
 * listed only if `GET /changes/{id}` would show it to the caller: in a document its tenant may reach, and authored by
 * the caller or on a document the caller may read. What the caller may not see is left out before the page is cut, and
 * nothing in the answer counts or names it. So is a change of a document the route's profile does not serve (`CTL-58`):
 * the service profile lists only what it would show by id. While the open-change sets are not kept (`CTL-54`) the listing is a 503:
 * it could miss a change only the list of the format before names.
 */
internal class ChangeListings(private val store: ControlStore, private val authority: ChangeAuthority) {
    suspend fun list(
        operator: Operator,
        document: SnapshotKey?,
        state: ChangeState?,
        after: String?,
        limit: Int,
        /** The documents the route's profile serves; a change of any other is left out as one the caller may not see. */
        documents: (SnapshotKey) -> Boolean = { true },
    ): ChangePage {
        if (!store.openChangesKept()) {
            throw ChangeRejectedException(
                UNAVAILABLE,
                "the open-change sets are not kept: run `cosec-migrate repair-references` to convert the store",
            )
        }
        val decisions = HashMap<SnapshotKey, ControlDecision>()
        val visible: suspend (ListedChange) -> Boolean = { listed ->
            documents(listed.document) && visible(operator, listed, decisions)
        }
        if (document == null) return byState(operator, checkNotNull(state), after, limit, documents, visible)
        if (!TenantIsolation.allows(operator, document) || !documents(document)) {
            return ChangePage(emptyList(), null)
        }
        val listed = store.openChanges(document)
            .filter { state == null || (it as? ListedChange.Readable)?.change?.state == state }
            .sortedBy { it.id.value }
            .filter { after == null || it.id.value > after }
            .filter { visible(it) }
        val page = listed.take(limit)
        return ChangePage(page, if (listed.size > limit) page.last().id.value else null)
    }

    /**
     * The page is cut after the filter, never before (`CTL-55`): the store skips, unread, the documents the caller's
     * tenant does not reach, and the rest are read page by page until [limit] visible ones and one more are found, so
     * [ChangePage.next] is the cursor of a change the caller sees, and there only when a visible one follows it.
     */
    private suspend fun byState(
        operator: Operator,
        state: ChangeState,
        after: String?,
        limit: Int,
        documents: (SnapshotKey) -> Boolean,
        visible: suspend (ListedChange) -> Boolean,
    ): ChangePage {
        val found = ArrayList<ListedChange>()
        var cursor = after
        while (found.size <= limit) {
            val page = store.changes(state, cursor, limit) { TenantIsolation.allows(operator, it) && documents(it) }
            page.changes.filterTo(found) { visible(it) }
            cursor = page.next ?: break
        }
        val shown = found.take(limit)
        return ChangePage(shown, if (found.size > limit) cursorOf(shown.last()) else null)
    }

    private suspend fun visible(
        operator: Operator,
        listed: ListedChange,
        decisions: MutableMap<SnapshotKey, ControlDecision>,
    ): Boolean {
        val key = listed.document
        if (!TenantIsolation.allows(operator, key)) return false
        if ((listed as? ListedChange.Readable)?.change?.author == operator.actor) return true
        return when (decisions.getOrPut(key) { authority.decide(operator, key, ControlActions.READ) }) {
            ControlDecision.PERMIT -> true
            ControlDecision.DENY -> false
            ControlDecision.INDETERMINATE ->
                throw ChangeRejectedException(
                    UNAVAILABLE,
                    "whether the changes of [$key] may be read could not be decided"
                )
        }
    }

    private companion object {
        /** As the stores order a state's members: `<docKey>#<id>`. */
        fun cursorOf(listed: ListedChange): String = "${listed.document}#${listed.id.value}"
    }
}
