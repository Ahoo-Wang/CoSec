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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

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
