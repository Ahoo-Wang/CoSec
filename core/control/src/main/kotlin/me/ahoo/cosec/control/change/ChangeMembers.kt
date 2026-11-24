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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredMembers
import me.ahoo.cosec.document.json.StoredMembers.Companion.opaque
import me.ahoo.cosec.document.json.StoredMembers.Companion.optional
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredMembers.Member
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord

/**
 * Every member of a change record and of a staging slot ([ChangeRequestCodec], `SNP-28`): what its absence is read as,
 * and whether ignoring it, or dropping it when a control host of another version rewrites the record, would let a
 * change through more easily. Each is read by every 8.x reader. The operations stay strict, read by the document
 * format (`CTL-53`): an operation this version does not understand makes the change unreadable, never one that does less.
 */
@InternalCoSecApi
object ChangeMembers {
    /** A member whose loss lets a change through more easily: ignored or dropped, a requirement lapses. */
    private fun guarding(path: String, whenAbsent: WhenAbsent) =
        Member(path, whenAbsent, ignoringWidens = true, droppingWidens = true)

    val CHANGE = StoredMembers(
        StoredRecord.CHANGE,
        StoredMembers.COMMON + listOf(
            required("id"),
            required("key"),
            required("base"),
            required("base.revision"),
            // The head the change was proposed on: lost, the conflict check against a moved head is not made. Absent
            // only from a base of revision 0, a document not yet published; a later revision's base without it is refused.
            guarding("base.digest", WhenAbsent.NONE),
            opaque("operations"),
            required("author"),
            required("author.tenant"),
            required("author.principal"),
            required("note"),
            required("kind"),
            required("state"),
            required("findings"),
            required("findings.check"),
            required("findings.path"),
            required("findings.message"),
            required("findings.status"),
            required("findings.severity"),
            optional("candidate", WhenAbsent.NONE),
            opaque("checkedWith"),
            required("createdAt"),
            required("updatedAt"),
            optional("skipShadow", WhenAbsent.NONE),
            optional("origin", WhenAbsent.NONE),
            optional("origin.asserted", WhenAbsent.FALSE),
            optional("origin.via", WhenAbsent.NONE),
            optional("origin.via.method", WhenAbsent.EMPTY),
            optional("origin.via.credential", WhenAbsent.NONE),
            optional("origin.via.request", WhenAbsent.NONE),
            optional("origin.peer", WhenAbsent.NONE),
            optional("publishedRevision", WhenAbsent.NONE),
            // What needs approval: lost, the change publishes without the approval its check asked for.
            guarding("escalations", WhenAbsent.EMPTY),
            required("escalations.subject"),
            required("escalations.reason"),
            required("escalations.detail"),
            // No approval: one is asked for again, which narrows.
            optional("approval", WhenAbsent.NONE),
            required("approval.approver"),
            required("approval.approver.tenant"),
            required("approval.approver.principal"),
            required("approval.at"),
            required("approval.candidate"),
            required("approval.covers"),
            required("approval.memberships"),
            opaque("approval.memberships.roles"),
            required("approval.memberships.attachments"),
            required("approval.memberships.source"),
            opaque("approval.attributes"),
            required("approval.principalBindings"),
            required("approval.breakGlass"),
            required("approval.credentialId"),
            required("approval.issuedAt"),
            // No window: none was started, so none can pass, which narrows.
            optional("shadow", WhenAbsent.NONE),
            required("shadow.window"),
            required("shadow.startedAt"),
            optional("shadow.closed", WhenAbsent.NONE),
            opaque("shadow.closed.counts"),
            required("shadow.closed.closedAt"),
            // A window that lacked samples, or was blocked: lost, it reads as one that passed.
            required("shadow.closed.insufficient"),
            required("shadow.closed.blocked"),
            optional("shadow.closed.vanished", WhenAbsent.EMPTY),
            optional("shadow.reporters", WhenAbsent.EMPTY),
            // What the author confirmed: lost, a DENY with onMissing: nomatch is asked to be confirmed again (narrows).
            optional("confirmations", WhenAbsent.EMPTY),
            required("confirmations.policy"),
            required("confirmations.statement"),
            required("confirmations.digest"),
            required("confirmations.kind"),
            optional("checkedApps", WhenAbsent.NONE),
            optional("checkedApps.digest", WhenAbsent.NONE),
        ),
    )

    val STAGING_SLOT = StoredMembers(
        StoredRecord.STAGING_SLOT,
        StoredMembers.COMMON + listOf(
            // The change that holds the slot: lost, another change stages the same document over it.
            guarding("holder", WhenAbsent.NONE),
            optional("since", WhenAbsent.NONE),
        ),
    )
}
