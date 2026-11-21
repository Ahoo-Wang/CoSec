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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** One run of [ControlClient.proposeAndPublishWithoutShadow]. */
internal class PublishWithoutShadow(
    private val client: ControlClient,
    private val caller: ControlCaller,
    private val skipReason: String,
    private val patience: Patience,
) {
    suspend fun run(document: SnapshotKey, operations: List<Operation>, note: String): Change {
        while (true) {
            val proposed = try {
                client.propose(caller, ChangeRequest(document, base(document), operations, note))
            } catch (e: ControlRefusedException) {
                if (!retried(e)) throw e
                continue
            }
            if (proposed.state != ChangeState.CHECKED) {
                // A check that found what stops it: nothing to wait for, and nothing left open.
                return if (proposed.state == ChangeState.DRAFT) client.abandon(caller, proposed.id) else proposed
            }
            publish(proposed)?.let { return it }
        }
    }

    /** The published change, or one that waits for a person; null when the head moved and the whole is to be made again. */
    private suspend fun publish(proposed: Change): Change? {
        val staged = stage(proposed) ?: return null
        if (staged.state != ChangeState.READY) return staged
        return try {
            client.publish(caller, staged.id)
        } catch (e: ControlRefusedException) {
            givenUp(staged, e)
            null
        }
    }

    /** [proposed] staged; null when it was abandoned to be made again on a moved head. */
    private suspend fun stage(proposed: Change): Change? {
        while (true) {
            try {
                return client.skipShadow(caller, proposed.id, skipReason)
            } catch (e: ControlRefusedException) {
                if (e.conflict in WAITED && (released(proposed, e.heldBy) || patience.next())) continue
                givenUp(proposed, e)
                return null
            }
        }
    }

    /**
     * Whether the slot was held by a change this one may abandon, and now is not: one of the same author, left before
     * it published (`CHECKED`, `SHADOWING`, `READY`), as a writer that stopped halfway leaves it. Anything else (another
     * author's change, one waiting for a person, one this caller may not read) is waited for.
     */
    private suspend fun released(proposed: Change, heldBy: String?): Boolean {
        if (heldBy == null || heldBy == proposed.id) return false
        val holder = try {
            client.change(caller, heldBy)
        } catch (_: ControlRefusedException) {
            return false
        }
        if (holder.author != proposed.author || holder.state !in LEFT_HALFWAY) return false
        return try {
            client.abandon(caller, holder.id)
            true
        } catch (_: ControlRefusedException) {
            false
        }
    }

    /** Abandons [change] after [e]; throws [e] unless the whole is to be made again on the moved head. */
    private suspend fun givenUp(change: Change, e: ControlRefusedException) {
        try {
            client.abandon(caller, change.id)
        } catch (abandoning: ControlRefusedException) {
            e.addSuppressed(abandoning)
            throw e
        }
        if (!retried(e)) throw e
    }

    /** Whether a try refused with [e] is made again, after its pause. */
    private suspend fun retried(e: ControlRefusedException): Boolean =
        (e.conflict == ConflictKind.BASE_MOVED || e.conflict in WAITED) && patience.next()

    /** The head's revision; 0 for a document not published yet, which the head's read answers as not found. */
    private suspend fun base(document: SnapshotKey): Long = try {
        client.document(caller, document).revision
    } catch (e: ControlRefusedException) {
        if (e.status != NOT_FOUND) throw e
        0
    }

    private companion object {
        val WAITED = setOf(ConflictKind.SLOT_HELD, ConflictKind.CONTENDED)
        val LEFT_HALFWAY = setOf(ChangeState.CHECKED, ChangeState.SHADOWING, ChangeState.READY)
        const val NOT_FOUND = 404
    }
}
