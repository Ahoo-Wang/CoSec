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

    /**
     * The published change, or one that waits for a person; null when the whole is to be made again: the head moved, or
     * another write took the change's place.
     */
    private suspend fun publish(proposed: Change): Change? {
        val staged = stage(proposed) ?: return null
        if (staged.state == ChangeState.CONFLICTED) return remade(staged)
        if (staged.state != ChangeState.READY) return staged
        val published = try {
            client.publish(caller, staged.id)
        } catch (e: ControlRefusedException) {
            givenUp(staged, e)
            return null
        }
        return if (published.state == ChangeState.CONFLICTED) remade(published) else published
    }

    /**
     * [conflicted], whose place another write took (`CONFLICTED`), abandoned: null to make the whole again within the
     * patience, or the abandoned change once the patience is spent.
     */
    private suspend fun remade(conflicted: Change): Change? {
        val abandoned = client.abandon(caller, conflicted.id)
        return if (patience.next()) null else abandoned
    }

    /** [proposed] staged; null when it was abandoned to be made again on a moved head. */
    private suspend fun stage(proposed: Change): Change? {
        while (true) {
            try {
                return client.skipShadow(caller, proposed.id, skipReason)
            } catch (e: ControlRefusedException) {
                if (waited(proposed, e)) continue
                givenUp(proposed, e)
                return null
            }
        }
    }

    /**
     * Whether staging [proposed] is tried again after [e]: a slot that is held, or contended, is waited for within the
     * patience. A holder this write may abandon is abandoned first, and the next try still waits its turn, so a slot
     * that stays held never makes it loop.
     */
    private suspend fun waited(proposed: Change, e: ControlRefusedException): Boolean {
        if (e.conflict !in WAITED) return false
        releaseLeftHalfway(proposed, e.heldBy)
        return patience.next()
    }

    /**
     * Abandons the holder [heldBy] when it was left by a writer that died: a change of the same author as [proposed],
     * left before it published (`CHECKED`, `SHADOWING`, `READY`), with no approval and no shadow window (a writer of
     * this kind records neither, so a holder with one is a person's work, which an abandon would lose silently,
     * DR1-F-15), and not updated for at least the patience. Every
     * writer of this kind abandons its own change once its patience is spent, so a holder that old has no writer left;
     * a younger one may be a live writer's, another replica acting as the same principal, and is waited for. Both times
     * are the control plane's (the holder's `updatedAt`, [proposed]'s `createdAt`), never this process's clock. Anything
     * else (another author's change, one waiting for a person, one this caller may not read) is waited for too.
     */
    private suspend fun releaseLeftHalfway(proposed: Change, heldBy: String?) {
        if (heldBy == null || heldBy == proposed.id) return
        try {
            val holder = client.change(caller, heldBy)
            if (holder.author == proposed.author && leftHalfway(holder) && abandonedBy(holder, proposed)) {
                client.abandon(caller, holder.id)
            }
        } catch (_: ControlRefusedException) {
            // Not readable, or no longer abandonable: waited for like any other holder.
        }
    }

    /**
     * Whether [holder] is what a writer of this kind leaves when it dies: before it published, never approved and never
     * shadowed. An approval or a window, open or closed, is evidence a person or live traffic gave; it is never thrown
     * away to free a slot.
     */
    private fun leftHalfway(holder: Change): Boolean =
        holder.state in LEFT_HALFWAY && holder.approval == null && holder.shadow == null

    /** Whether [holder] has not been updated for a whole patience before [proposed] was made: its writer gave up or died. */
    private fun abandonedBy(holder: Change, proposed: Change): Boolean =
        !holder.updatedAt.plus(patience.duration).isAfter(proposed.createdAt)

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
