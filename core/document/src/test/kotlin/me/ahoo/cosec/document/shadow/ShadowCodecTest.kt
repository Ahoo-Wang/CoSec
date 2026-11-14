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

package me.ahoo.cosec.document.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowSample
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** RFC 0007 §6.4, §6.5: candidates, shadow reports and instance statuses as JSON, read back strictly. */
class ShadowCodecTest {
    private val now: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val acme: SnapshotKey = SnapshotKey.Tenant("acme")
    private val document = SnapshotDocument(
        2,
        5,
        Instant.parse("2026-11-01T00:00:00Z"),
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray(),
    )

    @Test
    fun `candidates, reports and statuses read back as they were written`() {
        val candidate = ShadowCodec.decodeCandidate(ShadowCodec.encode(StoredCandidate("w", 4, document)))
        candidate.window.assert().isEqualTo("w")
        candidate.baseRevision.assert().isEqualTo(4)
        candidate.document.revision.assert().isEqualTo(5)
        val report = ShadowReport(
            "w",
            "i1",
            mapOf(ShadowOutcome.NARROWED to 2L),
            mapOf(
                ShadowOutcome.NARROWED to listOf(ShadowSample("acme", "bob", "order", "", "http:GET", "http//orders", "a[0]", null))
            ),
            setOf(4L),
            now,
            null,
        )
        ShadowCodec.decodeReport(ShadowCodec.encodeReport(report)).assert().isEqualTo(report)
        val status = InstanceStatus(
            "i1",
            now,
            mapOf(
                acme to InstalledDocument(4, Duration.ofMillis(250)),
                SnapshotKey.Platform to InstalledDocument(9, Duration.ZERO)
            )
        )
        ShadowCodec.decodeStatuses(
            ShadowCodec.encodeStatuses(mapOf("i1" to status))
        ).assert().isEqualTo(mapOf("i1" to status))
        listOf("{}", "[]", "not json", """{"window":"w","baseRevision":"x"}""").forEach { text ->
            assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeCandidate(text.encodeToByteArray()) }
        }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeReport("""{"window":"w"}""".encodeToByteArray())
        }
        // A negative count would cancel another instance's when the reports are summed; a ':' in an instance id
        // would make its key ambiguous.
        val valid = String(ShadowCodec.encodeReport(report))
        listOf(
            valid.replace(""""NARROWED":2""", """"NARROWED":-2"""),
            valid.replace(""""instance":"i1"""", """"instance":"x:i1""""),
        ).forEach { text ->
            text.assert().isNotEqualTo(valid)
            assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeReport(text.encodeToByteArray()) }
        }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeStatuses(
                """{"i1":{"at":"x","documents":{}}}""".encodeToByteArray()
            )
        }
        // SCP-10: an app no key can name is a format error, as a key no build ever wrote is.
        val encoded = String(ShadowCodec.encodeStatuses(mapOf("i1" to status)))
        val odd = encoded.replace(
            "\"documents\":{",
            "\"documents\":{\"app:a\\u0001b\":{\"revision\":1,\"lagMillis\":0},"
        )
        odd.assert().isNotEqualTo(encoded)
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeStatuses(odd.encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeStatuses(odd.replace("app:a\\u0001b", "app:a:b").encodeToByteArray())
        }
    }

    /** RFC 0010 C5: a status says which document format level its instance reads, and one that does not is refused. */
    @Test
    fun `an instance status carries its document format level`() {
        val status = InstanceStatus("a", Instant.parse("2026-11-08T00:00:00Z"), emptyMap())
        status.formatLevel.assert().isEqualTo(1)
        val encoded = ShadowCodec.encodeStatuses(mapOf("a" to status))
        encoded.toString(Charsets.UTF_8).assert().contains(""""formatLevel":1""")
        ShadowCodec.decodeStatuses(encoded)["a"]?.formatLevel.assert().isEqualTo(1)
    }

    @Test
    fun `a status without a format level is refused`() {
        val missing = """{"a":{"at":"2026-11-08T00:00:00Z","documents":{}}}"""
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeStatuses(missing.encodeToByteArray()) }
        val zero = """{"a":{"at":"2026-11-08T00:00:00Z","documents":{},"formatLevel":0}}"""
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeStatuses(zero.encodeToByteArray()) }
    }
}
