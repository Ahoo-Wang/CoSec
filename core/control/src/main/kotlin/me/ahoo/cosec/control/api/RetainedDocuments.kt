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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The documents a request of the control API reads, from the cache or the revisions: the head of a key, and a retained
 * revision of it.
 *
 * It reads whatever is stored and takes no operator: **each caller holds the authority for the key it asks for** before
 * it asks (`ChangeAuthority.require`/`holds`), as the control plane's reads, simulations and analyses do.
 */
internal class RetainedDocuments(
    private val revisions: Revisions,
    private val cache: DocumentCache,
) {
    /** The head of [key] from the cache, decoded and compiled once for its content (RC1-41); null when it has none. */
    suspend fun held(key: SnapshotKey): Held? = entry(key)?.let { Held(it.document, it) }

    suspend fun entry(key: SnapshotKey): DocumentCache.Entry? {
        val stored = revisions.stored(key) ?: return null
        return cache.get(key, stored.digest)
            ?: cache.put(key, stored.digest, stored.document.bodySize, revisions.decode(key, stored.document))
    }

    /**
     * [key] at [revision], as [retained] reads it: 410 when it is no longer kept. Taken from the cache when it holds that
     * content (the head's, say), never put in it (review M2 of #1240): each retained revision would be an entry of its
     * own, and explaining at revisions is rare, so it is decoded and compiled for the call.
     */
    suspend fun retainedHeld(key: SnapshotKey, revision: Long): Held {
        val digest = revisions.retainedDigest(key, revision) ?: throw gone(key, revision)
        cache.get(key, digest)?.let { return Held(it.document, it) }
        val record = revisions.record(key, digest) ?: throw gone(key, revision)
        return Held(revisions.decode(key, record), null)
    }

    suspend fun retained(key: SnapshotKey, revision: Long): PolicyDocument =
        revisions.revision(key, revision) ?: throw gone(key, revision)

    private fun gone(key: SnapshotKey, revision: Long) = ChangeRejectedException(
        GONE,
        "[$key] keeps no revision $revision"
    )

    private companion object {
        const val GONE = 410
    }
}
