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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.readRecord
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.cocacheDigest
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.snapshotDigest
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The conditional reads and writes the control store makes of its caches (`putIfMatch`, `deleteIfMatch`,
 * `getIfChanged`) and how it resolves a write whose outcome is unknown: imported by [CoCacheControlStore], so their
 * names are not names of the package.
 */
internal object ConditionalStores {
    const val MAX_LIST_ATTEMPTS = 16

    /**
     * [block], a command to the store, with what fails it said as the port says it (R2C-6, `CTL-52`): a value that does
     * not decode stays a [SnapshotFormatException]; everything else, a connection, a timeout, a refused command, a
     * conditional write that kept losing, is a [ControlStoreException], what the control API answers 503 for.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> answering(what: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ControlStoreException) {
        throw e
    } catch (e: RuntimeException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is SnapshotFormatException }) throw e
        throw ControlStoreException("$what: the store did not answer (${e.message})", e)
    }

    /** Read, change, `putIfMatch` of the list [name]: retried while another writer changed it in between. */
    suspend fun updateList(
        lists: Store<String, Map<String, Instant>>,
        name: String,
        next: (Map<String, Instant>) -> Map<String, Instant>,
    ): Unit = answering("The control list [$name]") {
        repeat(MAX_LIST_ATTEMPTS) {
            val current = read(lists, name)
            val value = next(current?.first.orEmpty())
            if (value == current?.first) {
                return@answering
            }
            if (put(
                    lists,
                    name,
                    current?.second,
                    value,
                    CoCacheControlStore.digest(ListedCodec, value)
                ) is Put.Written
            ) {
                return@answering
            }
        }
        throw IllegalStateException(
            "The control list [$name] kept changing; gave up after $MAX_LIST_ATTEMPTS attempts."
        )
    }

    /**
     * Removes [key] if its value, read now, is [wanted], and only while it still is (`deleteIfMatch`). A conflict is
     * read again: the value that replaced it may be wanted too. A removal whose outcome is unknown is read back: gone, or
     * another value, means it is not this caller's to remove any more.
     */
    suspend fun <K : Any, V : Any> deleteIf(store: Store<K, V>, key: K, wanted: (V) -> Boolean): Boolean =
        answering("[$key]") { deleteIfMatching(store, key, wanted) }

    private suspend fun <K : Any, V : Any> deleteIfMatching(store: Store<K, V>, key: K, wanted: (V) -> Boolean): Boolean {
        repeat(MAX_LIST_ATTEMPTS) {
            val (value, digest) = read(store, key) ?: return false
            if (!wanted(value)) {
                return false
            }
            val deleted = try {
                store.async().deleteIfMatch(key, cocacheDigest(digest)).await()
            } catch (error: CacheWriteException) {
                return removedAfter(store, key, digest, error)
            }
            if (deleted == ConditionalDelete.Deleted) {
                return true
            }
        }
        throw IllegalStateException(
            "[$key] kept changing while it was removed; gave up after $MAX_LIST_ATTEMPTS attempts."
        )
    }

    /**
     * Removes [key] only while it holds [expected] (`deleteIfMatch`): whether it did. A removal whose outcome is unknown
     * is read back, as [deleteIf]'s.
     */
    suspend fun <K : Any, V : Any> deleteIfDigest(store: Store<K, V>, key: K, expected: SnapshotDigest): Boolean =
        answering("[$key]") {
            try {
                store.async().deleteIfMatch(key, cocacheDigest(expected)).await() == ConditionalDelete.Deleted
            } catch (error: CacheWriteException) {
                removedAfter(store, key, expected, error)
            }
        }

    /**
     * Whether a `deleteIfMatch` of [key] over [digest] that failed with [error] removed it: read back when its outcome
     * is unknown. Still [digest] means it did not, and the failure is the answer.
     */
    private suspend fun <K : Any, V : Any> removedAfter(
        store: Store<K, V>,
        key: K,
        digest: SnapshotDigest,
        error: CacheWriteException,
    ): Boolean {
        val after = if (error.outcomeUnknown) read(store, key) else throw error
        if (after?.second == digest) throw error
        return after == null
    }

    sealed interface Put {
        object Written : Put

        class Conflict(val current: SnapshotDigest?) : Put
    }

    /**
     * `putIfMatch`. A write that failed with an unknown outcome is resolved by reading back: [mine] means it landed;
     * still [expected] means it did not, and the failure is the answer; anything else means another writer got there.
     * A write CoCache knows was not executed (`outcomeUnknown` false, such as a command rejected before it was sent) is
     * not read back: the failure is the answer, even when another writer stored the same bytes in between.
     */
    suspend fun <K : Any, V : Any> put(
        cache: Store<K, V>,
        key: K,
        expected: SnapshotDigest?,
        value: V,
        mine: SnapshotDigest
    ): Put = answering("[$key]") { putIfMatching(cache, key, expected, value, mine) }

    private suspend fun <K : Any, V : Any> putIfMatching(
        cache: Store<K, V>,
        key: K,
        expected: SnapshotDigest?,
        value: V,
        mine: SnapshotDigest
    ): Put {
        val async = cache.async()
        val result = try {
            async.putIfMatch(key, expected?.let(::cocacheDigest), value).await()
        } catch (error: CacheWriteException) {
            val read = if (error.outcomeUnknown) readBack(cache, key, mine) else null
            val current = (read as? ConditionalGet.Present)?.let { snapshotDigest(it.digest) }
            return when {
                read == ConditionalGet.Unchanged -> Put.Written
                read == null || current == expected -> throw error
                else -> Put.Conflict(current)
            }
        }
        return when (result) {
            is ConditionalPut.Written -> Put.Written
            is ConditionalPut.Conflict -> Put.Conflict(result.current?.let(::snapshotDigest))
        }
    }

    /** What [key] holds now, compared with [mine]; null when Redis cannot say, so the write's own failure stands. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private suspend fun <K : Any, V : Any> readBack(
        cache: Store<K, V>,
        key: K,
        mine: SnapshotDigest
    ): ConditionalGet<V>? =
        try {
            cache.async().getIfChanged(key, cocacheDigest(mine)).await()
        } catch (_: Exception) {
            null
        }

    /** [key]'s value and digest; a value that does not decode fails naming the record, [name] ([readRecord]). */
    suspend fun <K : Any, V : Any> read(cache: Store<K, V>, key: K, name: String = "[$key]"): Pair<V, SnapshotDigest>? =
        answering(name) { cache.readRecord(key, name)?.let { (value, digest) -> value to snapshotDigest(digest) } }
}
