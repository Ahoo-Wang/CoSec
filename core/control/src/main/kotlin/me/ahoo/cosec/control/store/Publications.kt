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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.ParentRevision
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.ShadowWindow
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** A head as the control plane works with it: decoded, with the store's digest. */
@InternalCoSecApi
class Head(val key: SnapshotKey, val document: PolicyDocument, val stored: StoredDocument) {
    val revision: Long get() = document.revision
    val digest: SnapshotDigest get() = stored.digest
}

/** Who and what publishes a revision (RFC 0007 §5.3); [Revisions.publish] adds the parent. */
@InternalCoSecApi
class Publication(
    val change: String,
    val kind: ProvenanceKind,
    val publishedBy: Actor,
    val approvedBy: List<Actor> = emptyList(),
    val shadow: ShadowWindow? = null,
)

/** The outcome of [Revisions.publish]. */
@InternalCoSecApi
sealed interface PublishOutcome {
    /** The head is now [revision]. */
    @InternalCoSecApi
    class Published(val revision: Long, val digest: SnapshotDigest) : PublishOutcome

    /**
     * Someone published since the base was read: nothing was overwritten, and the change must be rebased and checked
     * again (RFC 0007 §5.5). [current] is what the store holds.
     */
    @InternalCoSecApi
    class Conflicted(val current: SnapshotDigest?) : PublishOutcome

    /** The candidate removes policies other documents still refer to (RFC 0007 §5.8): nothing was written. */
    @InternalCoSecApi
    class Referenced(val referrers: Map<PolicyId, Set<SnapshotKey>>) : PublishOutcome
}

/** What [Revisions.verifyChain] found walking a head's `parent` chain. */
@InternalCoSecApi
sealed interface ChainCheck {
    /** Every parent down to [oldest] has its record; older ones were reclaimed or never existed. */
    @InternalCoSecApi
    class Intact(val oldest: Long) : ChainCheck

    /** The record [missing] should exist and does not: a lost write (a failover) or a deleted key. */
    @InternalCoSecApi
    class Broken(val missing: ParentRevision) : ChainCheck
}

/**
 * What [Revisions.reclaim] did: the [records] and [orphans] it took out of the index, the records it [deleted] (those
 * and any an earlier pass could not delete), and the ones whose deletion [failed], which the next pass tries again.
 */
@InternalCoSecApi
class Reclaimed(
    val records: List<SnapshotDigest>,
    val orphans: List<SnapshotDigest>,
    val deleted: List<SnapshotDigest> = records + orphans,
    val failed: List<SnapshotDigest> = emptyList(),
) {
    /** Whether it changed anything or has something left to do. */
    val any: Boolean get() = records.isNotEmpty() || orphans.isNotEmpty() || deleted.isNotEmpty() || failed.isNotEmpty()

    @InternalCoSecApi
    companion object {
        val NOTHING: Reclaimed = Reclaimed(emptyList(), emptyList())
    }
}
