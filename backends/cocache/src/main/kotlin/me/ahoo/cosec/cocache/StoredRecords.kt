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

package me.ahoo.cosec.cocache

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText

/**
 * One unconditional read of [key], the record [name] says in words: its value and digest, or null when there is none.
 * A value that does not decode fails whole (`SCP-10`) as a [SnapshotFormatException] that names the record beside the
 * codec's reason, however CoCache wrapped what the codec threw: an operator learns which record to repair or remove,
 * such as one an 8.0 preview wrote under a tenant or app id that holds `/` (#1403).
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <K : Any, V : Any> Store<K, V>.readRecord(key: K, name: String): Pair<V, Digest>? {
    val read = try {
        async().getIfChanged(key, null).await()
    } catch (e: RuntimeException) {
        val format = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SnapshotFormatException>().firstOrNull()
            ?: throw e
        throw SnapshotFormatException("${SnapshotKeyText.printable(name)} does not read: ${format.message}", e)
    }
    return when (read) {
        is ConditionalGet.Present -> read.value to read.digest
        ConditionalGet.Unchanged -> error("An unconditional read of $name was answered as unchanged.")
        ConditionalGet.Absent -> null
    }
}
