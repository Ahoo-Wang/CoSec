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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText

/**
 * A change record as the store holds it, its bytes, decoded only when [decode] is asked (DR2-E-4): a reader of a
 * document's open changes learns each one's size before anything is decoded, and weighs it by that. The bytes are those
 * [ChangeRequestCacheCodec] writes, so a record reads the same whichever of the two wrote it.
 */
internal class StoredChange(val bytes: ByteArray) {
    /** The change, decoded now; a record that does not decode throws [SnapshotFormatException], naming [name]. */
    fun decode(name: String): ChangeRequest = try {
        ChangeRequestCodec.decode(bytes)
    } catch (e: SnapshotFormatException) {
        throw SnapshotFormatException("${SnapshotKeyText.printable(name)} does not read: ${e.message}", e)
    }

    /** The store's codec: the bytes as they are, nothing decoded. */
    companion object : Codec<StoredChange> {
        override fun encode(value: StoredChange): ByteArray = value.bytes

        override fun decode(bytes: ByteArray): StoredChange = StoredChange(bytes)

        /** [change] as the store writes it. */
        fun of(change: ChangeRequest): StoredChange = StoredChange(ChangeRequestCacheCodec.encode(change))
    }
}
