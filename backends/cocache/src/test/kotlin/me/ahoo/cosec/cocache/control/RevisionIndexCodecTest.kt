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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** `CTL-84`: an index entry names its record's size, and no entry takes more than the bound its count is derived from. */
class RevisionIndexCodecTest {
    private fun digest(fill: Int) = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { fill.toByte() })

    @Test
    fun `an entry's size reads back, and an entry without one is written without it`() {
        val sized = RevisionEntry(1, digest(1), Instant.EPOCH, null, size = 1_234)
        val unsized = RevisionEntry(2, digest(2), Instant.EPOCH, digest(1))

        val read = RevisionIndexCodec.decode(RevisionIndexCodec.encode(RevisionIndex(entries = listOf(sized, unsized))))

        read.entries.assert().containsExactly(sized, unsized)
        RevisionIndexCodec.encode(RevisionIndex(entries = listOf(unsized))).decodeToString()
            .assert().doesNotContain("\"size\"")
    }

    @Test
    fun `the largest entry takes at most the bound the index's count is derived from`() {
        // Every member at its longest: the largest revision and size, a timestamp with nanoseconds, both digests.
        val largest = RevisionEntry(
            Long.MAX_VALUE,
            digest(0xff),
            Instant.parse("9999-12-31T23:59:59.999999999Z"),
            digest(0xfe),
            size = Long.MAX_VALUE,
        )
        // Ascending, as an index is: the last revisions there can be, each as long as the largest.
        fun entries(count: Int) = List(count) { largest.copy(revision = Long.MAX_VALUE - count + 1 + it) }
        fun encoded(count: Int) = RevisionIndexCodec.encode(RevisionIndex(entries = entries(count))).size.toLong()

        val first = encoded(1) - encoded(0)
        val next = encoded(2) - encoded(1)

        first.assert().isLessThanOrEqualTo(RetentionPolicy.INDEX_ENTRY_BYTES)
        next.assert().describedAs(
            "an entry and its separator take $next"
        ).isLessThanOrEqualTo(RetentionPolicy.INDEX_ENTRY_BYTES)
    }
}
