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

// cosec-spi's tests cannot see cosec-document's SnapshotDocuments: they make a document as it does.
@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.spi.shadow

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** RFC 0007 §6.4, §6.5: the shadow window's values are compared by value, and a candidate names its window. */
class ShadowValuesTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val document = SnapshotDocument(2, 5, at, """{"schema":2}""".encodeToByteArray())

    /** The default keeps the meaning of [ShadowReportReader.reports]: it throws where that throws, and names none unreadable. */
    @Test
    fun `the default readings read the reports, and throw where they throw`() = runBlocking<Unit> {
        val report = ShadowReport("c1@1", "i0", mapOf(ShadowOutcome.SAME to 1L), emptyMap(), emptySet(), null, null)
        var failing = false
        val reader = object : ShadowReportReader {
            override suspend fun reports(window: String): List<ShadowReport> =
                if (failing) error("[c1@1:i1] is listed as reported but has no report") else listOf(report)

            override suspend fun statuses(): List<InstanceStatus> = emptyList()

            override suspend fun residents(instances: Set<String>) = ResidentReadings(emptyMap(), instances)
        }
        reader.readings("c1@1").assert().isEqualTo(ShadowReadings(listOf(report), emptySet()))
        reader.readings("c1@1").hashCode().assert().isEqualTo(ShadowReadings(listOf(report), emptySet()).hashCode())
        reader.readings("c1@1").toString().assert().contains("unreadable=[]")
        failing = true
        runCatching {
            reader.readings(
                "c1@1"
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `resident documents are their instance, time and revisions, and readings name the unreadable`() {
        val documents = mapOf<SnapshotKey, Long>(SnapshotKey.Platform to 9, SnapshotKey.Tenant("acme") to 4)
        val resident = ResidentDocuments("i1", at, documents)
        resident.assert().isEqualTo(ResidentDocuments("i1", at, documents))
        resident.hashCode().assert().isEqualTo(ResidentDocuments("i1", at, documents).hashCode())
        resident.assert().isNotEqualTo(ResidentDocuments("i2", at, documents))
        resident.assert().isNotEqualTo(ResidentDocuments("i1", at, mapOf(SnapshotKey.Platform to 9L)))
        (resident as Any).assert().isNotEqualTo(documents)
        resident.toString().assert().startsWith("ResidentDocuments(i1, 2026-11-01T00:00:00Z, ")
        val readings = ResidentReadings(mapOf("i1" to resident), setOf("i2"))
        readings.assert().isEqualTo(ResidentReadings(mapOf("i1" to resident), setOf("i2")))
        readings.hashCode().assert().isEqualTo(ResidentReadings(mapOf("i1" to resident), setOf("i2")).hashCode())
        readings.assert().isNotEqualTo(ResidentReadings(mapOf("i1" to resident), emptySet()))
        readings.toString().assert().contains("unreadable=[i2]")
    }

    /** `CTL-75`, in memory: a resident record is read for the instances asked, until its life ends; any other is missing. */
    @Test
    fun `the in-memory residents answer what was written within its life and name the rest`() = runBlocking<Unit> {
        var now = at
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
        val reports = InMemoryShadowReports(clock)
        val resident = ResidentDocuments("i1", at, mapOf(SnapshotKey.Platform to 9L))
        reports.write(resident)
        reports.residents(setOf("i1", "i2")).assert()
            .isEqualTo(ResidentReadings(mapOf("i1" to resident), setOf("i2")))
        now = at.plus(ShadowReportReader.RESIDENT_TTL).plus(Duration.ofSeconds(1))
        reports.residents(setOf("i1")).unreadable.assert().containsExactly("i1")
    }

    @Test
    fun `an instance status is its instance, time and format level`() {
        val status = InstanceStatus("i1", at)
        status.formatLevel.assert().isEqualTo(InstanceStatus.FORMAT_LEVEL)
        status.assert().isEqualTo(InstanceStatus("i1", at))
        status.hashCode().assert().isEqualTo(InstanceStatus("i1", at).hashCode())
        status.assert().isNotEqualTo(InstanceStatus("i2", at))
        status.assert().isNotEqualTo(InstanceStatus("i1", at, formatLevel = 2))
        (status as Any).assert().isNotEqualTo("i1")
        status.toString().assert().startsWith("InstanceStatus(i1, 2026-11-01T00:00:00Z, ")
    }

    @Test
    fun `a candidate names its window and the revision of its base`() {
        StoredCandidate("w", 0, document).baseRevision.assert().isEqualTo(0)
        assertThrownBy<IllegalArgumentException> { StoredCandidate("", 4, document) }
        assertThrownBy<IllegalArgumentException> { StoredCandidate("w", -1, document) }
    }

    @Test
    fun `the staged list names the documents it lists, compares by them, and a store keeps none by default`() {
        val acme = SnapshotKey.Tenant("acme")
        val listed = StagedCandidates(mapOf(acme to setOf("c1@1")))
        listed.isStaged(acme).assert().isTrue()
        listed.isStaged(SnapshotKey.Platform).assert().isFalse()
        listed.assert().isEqualTo(StagedCandidates(mapOf(acme to setOf("c1@1"))))
        listed.hashCode().assert().isEqualTo(StagedCandidates(mapOf(acme to setOf("c1@1"))).hashCode())
        listed.assert().isNotEqualTo(StagedCandidates.NONE)
        listed.assert().isNotEqualTo("c1@1")
        listed.toString().assert().contains("c1@1")
        val store = object : CandidateStore {
            override suspend fun candidate(key: SnapshotKey): StoredCandidate? = null
        }
        kotlinx.coroutines.runBlocking { store.staged() }.assert().isNull()
    }
}
