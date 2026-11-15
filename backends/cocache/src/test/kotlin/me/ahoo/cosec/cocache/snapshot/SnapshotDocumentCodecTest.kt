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

import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Redis stores the body as it is: what `SnapshotDocuments.read` reads, and what [SnapshotDocumentCodec.encode] writes. */
class SnapshotDocumentCodecTest {
    private val body = """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"p"}]}"""

    @Test
    fun storesTheBodyUnchangedAndReadsItBack() {
        val document = SnapshotDocuments.read(body.encodeToByteArray())
        SnapshotDocumentCodec.encode(document).decodeToString().assert().isEqualTo(body)
        SnapshotDocumentCodec.decode(SnapshotDocumentCodec.encode(document)).assert().isEqualTo(document)
    }

    @Test
    fun refusesWhatTheReaderRefuses() {
        assertThrows<SnapshotFormatException> {
            SnapshotDocumentCodec.decode(
                """{"policies":[]}""".encodeToByteArray()
            )
        }
    }
}
