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

package me.ahoo.cosec.control.audit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * What happened to a change (RFC 0007 §10.5). Never carries a document's content: the change id, the document, the
 * base and candidate it was about, what needed approval, the confirmations, the shadow summary and the note; for
 * [Kind.HEADS_MOVED] and [Kind.REVIEW_NEEDED], the heads that [moved] and the escalations the analysis finds now.
 */
class ChangeEvent(
    val id: String,
    val time: Instant,
    val kind: Kind,
    val operator: Actor,
    val change: ChangeId,
    val document: SnapshotKey,
    val base: RevisionRef?,
    val candidate: RevisionRef?,
    val escalations: List<Escalation> = emptyList(),
    val confirmations: List<Confirmation> = emptyList(),
    val shadow: ShadowSummary? = null,
    val note: String = "",
    val moved: List<MovedHead> = emptyList(),
) {
    /**
     * A set that grows: a minor release may add a member (RFC 0010 Q6), so a `when` over it needs an `else`.
     */
    enum class Kind {
        PROPOSED,
        CHECKED,
        SHADOW_STARTED,
        SHADOWED,
        APPROVED,
        REJECTED,
        PUBLISHING,
        PUBLISHED,
        FAILED,
        BREAK_GLASS_REQUESTED,
        BREAK_GLASS_APPROVED,
        REVOKED,
        BOOTSTRAP,
        MIGRATE,

        /**
         * A change was published while another document it was checked with moved (RC1-33): the analysis, run again on
         * the current heads, finds nothing the publish did not have approved. Recorded, nothing to do.
         */
        HEADS_MOVED,

        /**
         * As [HEADS_MOVED], but the analysis run again finds an escalation the publish did not have approved, or could
         * not run: the published change may need a person's review (RC1-33). Nothing was rolled back.
         */
        REVIEW_NEEDED,

        /**
         * A change was abandoned (RC1-51): the operator is who abandoned it, and the note names its author. A
         * break-glass change's abandon waits for every REQUIRED listener before it takes effect, though the kind is not
         * [gated]; any other change's reaches the listeners best effort, after.
         */
        ABANDONED,
        ;

        /**
         * Whether the step waits for every REQUIRED listener before it takes effect (RFC 0007 §10.5, Q21): publishing,
         * and every step of creating break-glass (§8). Abandoning a break-glass change waits too ([ABANDONED]).
         */
        val gated: Boolean get() = this == PUBLISHING || this == BREAK_GLASS_REQUESTED || this == BREAK_GLASS_APPROVED
    }

    override fun toString(): String = "ChangeEvent($kind, change=${change.value}, document=$document, by=${operator.principal.value})"
}
