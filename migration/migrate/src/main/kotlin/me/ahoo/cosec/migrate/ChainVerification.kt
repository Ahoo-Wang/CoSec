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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * `verify-chain` (RFC 0007 §5.4): walks the `parent` chain of each document's head, as [Revisions.verifyChain] does, and
 * reports a revision whose record is missing, the trace a write lost in a Redis failover leaves. It reads, and writes
 * nothing, so it may run while the control plane runs.
 *
 * It also reports a revision index that does not follow the head's chain (a failover or a partial restore left it
 * behind, or on another branch): read only, like the walk, and repaired by the next read of the document's history.
 *
 * Every document [ControlStore.documents] lists is verified, or only [only], or those whose key starts with [prefix],
 * at most [limit] of them (R2C-20); one that cannot be read is reported with why, never skipped silently. Each is told
 * to [onEach] as it is verified, so a long run says how far it got and one that dies has printed what it verified.
 */
class ChainVerification(private val store: ControlStore) {
    private val revisions = Revisions(store)

    @Suppress("TooGenericExceptionCaught")
    fun run(
        only: SnapshotKey? = null,
        prefix: String? = null,
        limit: Int? = null,
        onEach: (VerifiedChain) -> Unit = {},
    ): List<VerifiedChain> = runBlocking {
        val keys = only?.let(::listOf) ?: store.documents().map { it.toString() to it }
            .filter { (text, _) -> prefix == null || text.startsWith(prefix) }.sortedBy { it.first }
            .let { listed -> limit?.let(listed::take) ?: listed }.map { it.second }
        keys.map { key ->
            try {
                VerifiedChain(key, revisions.verifyChain(key), null, revisions.verifyIndex(key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VerifiedChain(key, null, e.message ?: e.javaClass.name)
            }.also(onEach)
        }
    }
}
