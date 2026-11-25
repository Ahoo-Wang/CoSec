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
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowSample
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** RFC 0007 §6.4, §6.5: candidates, shadow reports and instance statuses as JSON, read back strictly. */
class ShadowCodecTest {
    private val now: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val document = SnapshotDocuments.read(
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray()
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
            now
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
        // SCP-10: in a resident record, an app no key can name is a format error, as a key no build ever wrote is.
        val resident = ResidentDocuments("i1", Instant.parse("2026-11-08T00:00:00Z"), mapOf(SnapshotKey.Platform to 3L))
        val encoded = String(ShadowCodec.encodeResident(resident))
        val odd = encoded.replace("\"documents\":{", "\"documents\":{\"app:a\\u0001b\":1,")
        odd.assert().isNotEqualTo(encoded)
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeResident(odd.encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeResident(odd.replace("app:a\\u0001b", "app:a:b").encodeToByteArray())
        }
    }

    /**
     * `CTL-75`: a resident record is the revision of each document its instance holds, and reads back as written; a
     * revision that is not a positive integer, or a record of a later format, is a format error (`CTL-53`).
     */
    @Test
    fun `resident documents read back as written, and a wrong revision is a format error`() {
        val resident = ResidentDocuments(
            "i1",
            Instant.parse("2026-11-08T00:00:00Z"),
            mapOf(SnapshotKey.Platform to 9L, SnapshotKey.App("orders") to 4L, SnapshotKey.Tenant("acme") to 12L),
        )
        val encoded = ShadowCodec.encodeResident(resident)
        ShadowCodec.decodeResident(encoded).assert().isEqualTo(resident)
        val text = String(encoded)
        text.assert().contains("\"tenant:acme\":12").doesNotContain("lag")
        for (wrong in listOf("0", "-1", "\"12\"", "1.5")) {
            assertThrownBy<SnapshotFormatException> {
                ShadowCodec.decodeResident(
                    text.replace("\"tenant:acme\":12", "\"tenant:acme\":$wrong").encodeToByteArray()
                )
            }
        }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeResident(text.replace("\"format\":1", "\"format\":2").encodeToByteArray())
        }
        // A member a later version adds is ignored (CTL-53).
        ShadowCodec.decodeResident(
            text.replaceFirst("{", "{\"later\":true,").encodeToByteArray()
        ).assert().isEqualTo(resident)
    }

    /**
     * From 8.0.0-beta.6 a status carries no documents. A status of 8.0.0-beta.5, which does, still reads: the member is
     * one this version does not know, and is ignored (`CTL-53`). Its instance has no resident record, so `propagation`
     * counts it unknown (`CTL-75`).
     */
    @Test
    fun `a status of 8_0_0-beta_5 reads, its documents ignored`() {
        val beta5 = """{"gw-1":{"format":1,"at":"2026-11-21T00:00:00Z","formatLevel":1,""" +
            """"documents":{"platform":{"revision":9,"lagMillis":1234},"tenant:acme":{"revision":4,"lagMillis":0}},""" +
            """"windows":["c1@1"]}}"""
        val status = ShadowCodec.decodeStatuses(beta5.encodeToByteArray()).getValue("gw-1")
        status.assert().isEqualTo(
            InstanceStatus("gw-1", Instant.parse("2026-11-21T00:00:00Z"), windows = setOf("c1@1"), release = null)
        )
        String(ShadowCodec.encodeStatuses(mapOf("gw-1" to status))).assert().doesNotContain("documents")
    }

    /**
     * `SNP-25`: a status names the release its instance runs. One of a release before it does not, and reads as an
     * unknown release (null), which nothing in 8.0 counts or waits on; a release that is not a string is a format error.
     * A status built without one is of an unknown release too: rebuilding what an instance wrote never names the reading
     * process's release.
     */
    @Test
    fun `a status names its release, and one without it reads as an unknown release`() {
        val at = Instant.parse("2026-11-21T00:00:00Z")
        val status = InstanceStatus("gw-1", at, release = "8.0.0-beta.9")
        val written = String(ShadowCodec.encodeStatuses(mapOf("gw-1" to status)))
        written.assert().contains("\"release\":\"8.0.0-beta.9\"")
        ShadowCodec.decodeStatuses(written.encodeToByteArray()).getValue("gw-1").assert().isEqualTo(status)
        // Built without one: unknown, never the reading process's own release (only the instance's reporter passes it).
        InstanceStatus("gw-1", at).release.assert().isNull()
        val beta8 = """{"gw-1":{"format":1,"at":"2026-11-21T00:00:00Z","formatLevel":1}}"""
        ShadowCodec.decodeStatuses(beta8.encodeToByteArray()).getValue("gw-1").release.assert().isNull()
        String(ShadowCodec.encodeStatuses(mapOf("gw-1" to InstanceStatus("gw-1", at, release = null))))
            .assert().doesNotContain("release")
        val wrong = """{"gw-1":{"format":1,"at":"2026-11-21T00:00:00Z","formatLevel":1,"release":9}}"""
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeStatuses(wrong.encodeToByteArray()) }
    }

    /** RFC 0010 C5: a status says which document format level its instance reads, and one that does not is refused. */
    @Test
    fun `an instance status carries its document format level`() {
        val status = InstanceStatus("a", Instant.parse("2026-11-08T00:00:00Z"))
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

    /** SNP-12: one shape. A record that states the header beside the document, as 8.0.0-alpha.4 wrote it, is refused. */
    @Test
    fun `a candidate record that states its header beside its document is refused`() {
        val record = """{"window":"w","baseRevision":4,"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"document":{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z"}}"""
        assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeCandidate(record.encodeToByteArray()) }
    }

    /** `CTL-10`: the windows an instance acknowledges are an optional member; absent is an instance that sends none. */
    @Test
    fun `a status keeps the windows its instance acknowledges, and one without them decodes as none sent`() {
        val at = Instant.parse("2026-11-21T00:00:00Z")
        val acknowledging = InstanceStatus("i1", at, windows = setOf("c2@1", "c1@1"))
        val silent = InstanceStatus("i2", at)
        val statuses = mapOf("i1" to acknowledging, "i2" to silent)
        ShadowCodec.decodeStatuses(ShadowCodec.encodeStatuses(statuses)).assert().isEqualTo(statuses)
        String(ShadowCodec.encodeStatuses(mapOf("i2" to silent))).assert().doesNotContain("windows")
        // A status an older version wrote: no member, no acknowledgement.
        val older = """{"i3":{"format":1,"at":"2026-11-21T00:00:00Z","formatLevel":1,"documents":{}}}"""
        ShadowCodec.decodeStatuses(older.encodeToByteArray()).getValue("i3").windows.assert().isNull()
        // One that does not shadow says so; one that shadows writes nothing for it, as an older version.
        val notShadowing = InstanceStatus("i5", at, shadows = false)
        ShadowCodec.decodeStatuses(ShadowCodec.encodeStatuses(mapOf("i5" to notShadowing))).getValue("i5")
            .assert().isEqualTo(notShadowing)
        String(ShadowCodec.encodeStatuses(mapOf("i2" to silent))).assert().doesNotContain("shadows")
        ShadowCodec.decodeStatuses(older.encodeToByteArray()).getValue("i3").shadows.assert().isTrue()
        listOf(""""windows":"c1@1"""", """"windows":[1]""", """"shadows":"no"""").forEach { member ->
            val broken = """{"i4":{"format":1,"at":"2026-11-21T00:00:00Z","formatLevel":1,"documents":{},$member}}"""
            assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeStatuses(broken.encodeToByteArray()) }
        }
    }
}
