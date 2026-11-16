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
 * Every document [ControlStore.documents] lists is verified, or only [only]; one that cannot be read is reported with
 * why, never skipped silently.
 */
class ChainVerification(private val store: ControlStore) {
    private val revisions = Revisions(store)

    @Suppress("TooGenericExceptionCaught")
    fun run(only: SnapshotKey? = null): List<VerifiedChain> = runBlocking {
        val keys = only?.let(::listOf) ?: store.documents().sortedBy { it.toString() }
        keys.map { key ->
            try {
                VerifiedChain(key, revisions.verifyChain(key), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VerifiedChain(key, null, e.message ?: e.javaClass.name)
            }
        }
    }
}
