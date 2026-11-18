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

package me.ahoo.cosec.cocache.snapshot

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotDocument

/**
 * Stores a [SnapshotDocument] as its body: the whole JSON document, header included (RFC 0003 §3.3).
 *
 * - [decode] is [SnapshotDocuments.read]: the header is read from the bytes by the streaming parser, no data binding
 *   (RFC 0003 §10), and the bytes are kept for the compiler.
 * - [encode] writes the body unchanged, so equal documents have equal bytes and CoCache's digest is stable. Its header
 *   was read from that body (SNP-12), so there is nothing to compare.
 *
 * A failure throws `SnapshotFormatException`; CoCache reports it as unreadable content (`ForeignFormatException`).
 */
internal object SnapshotDocumentCodec : Codec<SnapshotDocument> {
    override fun encode(value: SnapshotDocument): ByteArray = value.bodyBytes()

    override fun decode(bytes: ByteArray): SnapshotDocument = SnapshotDocuments.read(bytes)
}
