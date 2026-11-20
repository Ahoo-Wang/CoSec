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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.AppListCodec
import me.ahoo.cosec.cocache.control.ChangeRequestCacheCodec
import me.ahoo.cosec.cocache.control.ListedCodec
import me.ahoo.cosec.cocache.control.ReferrerMemberCodec
import me.ahoo.cosec.cocache.control.RevisionIndexCodec
import me.ahoo.cosec.cocache.control.StageSlotCodec
import me.ahoo.cosec.cocache.shadow.InstanceIndexCodec
import me.ahoo.cosec.cocache.shadow.InstanceStatusCodec
import me.ahoo.cosec.cocache.shadow.ReportersCodec
import me.ahoo.cosec.cocache.shadow.ShadowReportCodec
import me.ahoo.cosec.cocache.shadow.StoredCandidateCodec
import me.ahoo.cosec.cocache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Every record CoSec stores in Redis through `cosec-cocache`, listed with how a reader treats it (CTL-52, CTL-53,
 * IDN-31). A wrong value of any of them is a [SnapshotFormatException]: never an argument or JSON error, which the
 * control store would read as the store not answering and retry for ever. A record with a format ignores members of a
 * known format it does not know, reads a record without a format as format 1, and refuses a later format. A codec
 * added to the module that this list does not name fails [every stored codec is listed].
 */
class StoredRecordsTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val digest = SnapshotDigest(ByteArray(32) { it.toByte() })
    private val document = SnapshotDocuments.read(
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray()
    )

    /** A stored record: its codec, a value of it, and whether it carries a format (else it is strict, and why). */
    private class Record(
        val name: String,
        val encode: () -> ByteArray,
        val decode: (ByteArray) -> Any,
        val reencode: (Any) -> ByteArray,
        /** Where the format member is: the record itself, or (for a status) the one instance's object; null: strict. */
        val formatAt: ((ObjectNode) -> ObjectNode)? = { it },
        val strictBecause: String? = null,
        val wrong: List<String> = listOf("", "x", "[1]", """{"format":"one"}"""),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> record(
        name: String,
        value: T,
        encode: (T) -> ByteArray,
        decode: (ByteArray) -> T,
        formatAt: ((ObjectNode) -> ObjectNode)? = { it },
        strictBecause: String? = null,
        wrong: List<String> = listOf("", "x", "[1]", """{"format":"one"}"""),
    ) = Record(name, { encode(value) }, decode, { encode(it as T) }, formatAt, strictBecause, wrong)

    private val records = listOf(
        record(
            "ChangeRequestCacheCodec",
            ChangeRequest(
                id = ChangeId("c1"), key = SnapshotKey.Tenant("acme"), base = Base(0, null), operations = emptyList(),
                author = Actor(TenantId("acme"), PrincipalId("alice")), note = "n", kind = ProvenanceKind.PUBLISH,
                state = ChangeState.DRAFT, findings = emptyList(), candidate = null, checkedWith = emptyMap(),
                createdAt = at, updatedAt = at,
            ),
            ChangeRequestCacheCodec::encode,
            ChangeRequestCacheCodec::decode,
        ),
        record("StageSlotCodec", StageSlot(ChangeId("c1"), at), StageSlotCodec::encode, StageSlotCodec::decode),
        record(
            "RevisionIndexCodec",
            RevisionIndex(listOf(RevisionEntry(3, digest, at)), floor = 2),
            RevisionIndexCodec::encode,
            RevisionIndexCodec::decode,
        ),
        record("AppListCodec", AppList(setOf("order"), complete = true), AppListCodec::encode, AppListCodec::decode),
        record(
            "StoredCandidateCodec",
            StoredCandidate("c1@1", 4, document),
            StoredCandidateCodec::encode,
            StoredCandidateCodec::decode,
        ),
        record(
            "ShadowReportCodec",
            ShadowReport("c1@1", "i1", mapOf(ShadowOutcome.SAME to 2L), emptyMap(), setOf(4L), at, at),
            ShadowReportCodec::encode,
            ShadowReportCodec::decode,
        ),
        record(
            "InstanceStatusCodec",
            InstanceStatus("i1", at, mapOf(SnapshotKey.Platform to InstalledDocument(3, Duration.ofMillis(5))), 1),
            InstanceStatusCodec::encode,
            InstanceStatusCodec::decode,
            formatAt = { root -> root.properties().single().value as ObjectNode },
        ),
        record("ReportersCodec", setOf("i1", "i2"), ReportersCodec::encode, ReportersCodec::decode),
        record("InstanceIndexCodec", mapOf("i1" to at), InstanceIndexCodec::encode, InstanceIndexCodec::decode),
        record(
            "TokenFamilyCodec",
            TokenFamily("f1", TenantId("acme"), PrincipalId("alice"), at, at.plusSeconds(60), 2, at, false),
            TokenFamilyCodec::encode,
            TokenFamilyCodec::decode,
        ),
        record(
            "SnapshotDocumentCodec",
            document,
            SnapshotDocumentCodec::encode,
            SnapshotDocumentCodec::decode,
            formatAt = null,
            strictBecause = "a policy document is a decision input: its `schema` decides how it reads",
            wrong = listOf("", "x", "[1]", """{"schema":2}"""),
        ),
        record(
            "MembershipsCodec",
            me.ahoo.cosec.model.Memberships(me.ahoo.cosec.model.Memberships.Source.PROVIDER, emptyMap(), emptyList()),
            MembershipsCodec::encode,
            MembershipsCodec::decode,
            formatAt = null,
            strictBecause = "a cache entry of a decision input: one this version cannot read is loaded again",
            wrong = listOf("", "x", "[1]", """{"roles":{}}"""),
        ),
        record(
            "ReferrerMemberCodec",
            me.ahoo.cosec.control.store.ReferrerEntry(SnapshotKey.Tenant("acme"), 3),
            ReferrerMemberCodec::encode,
            ReferrerMemberCodec::decode,
            formatAt = null,
            strictBecause = "a back-reference member: the removal checks rest on it, so one it cannot read fails closed (CTL-42)",
            wrong = listOf("", "x", "tenant:acme@", "tenant:acme@0", "nowhere@1"),
        ),
        record(
            "ListedCodec",
            mapOf("tenant:acme" to at),
            ListedCodec::encode,
            ListedCodec::decode,
            formatAt = null,
            strictBecause = "the lists before 8.0.0-beta.5, only ever read to be converted",
            wrong = listOf("", "x", "[1]", """{"tenant:acme":1}"""),
        ),
    )

    @Test
    fun `every stored codec is listed`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val codecs = sources.flatMap { file ->
            Regex("""object (\w+) : Codec<""").findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()
        codecs.assert().describedAs("codecs of cocache the list of stored records does not name")
            .isEqualTo(records.map { it.name }.toSet())
    }

    @Test
    fun `a wrong value of every stored record is a format error`() {
        for (record in records) {
            for (wrong in record.wrong) {
                val error = runCatching { record.decode(wrong.encodeToByteArray()) }.exceptionOrNull()
                error.assert().describedAs("${record.name} reading [$wrong]").isInstanceOf(SnapshotFormatException::class.java)
            }
        }
        // The API key is read as a string, by its own codec: strict, an input of authentication.
        for (wrong in listOf("", "x", "[1]", """{"format":3}""")) {
            runCatching { ApiKeyCodec.decode(wrong) }.exceptionOrNull().assert().describedAs("ApiKeyCodec reading [$wrong]")
                .isInstanceOf(SnapshotFormatException::class.java)
        }
    }

    @Test
    fun `every record with a format ignores what it does not know, reads one without a format, and refuses a later one`() {
        for (record in records) {
            val formatAt = record.formatAt
            if (formatAt == null) {
                record.strictBecause.assert().describedAs("${record.name} is strict, and says why").isNotBlank()
                continue
            }
            val written = record.encode()
            fun changed(change: (ObjectNode) -> Unit): ByteArray {
                val root = CoSecJson.mapper.readTree(written) as ObjectNode
                change(formatAt(root))
                return CoSecJson.mapper.writeValueAsBytes(root)
            }
            formatAt(CoSecJson.mapper.readTree(written) as ObjectNode).get("format").asInt()
                .assert().describedAs("${record.name} writes format 1").isEqualTo(1)
            val again = { bytes: ByteArray -> record.reencode(record.decode(bytes)).decodeToString() }
            again(changed { it.put("aLaterMember", true) }).assert().describedAs("${record.name}: an unknown member")
                .isEqualTo(written.decodeToString())
            again(changed { it.remove("format") }).assert().describedAs("${record.name}: written before formats")
                .isEqualTo(written.decodeToString())
            runCatching { record.decode(changed { it.put("format", 2) }) }.exceptionOrNull()
                .assert().describedAs("${record.name}: a later format").isInstanceOf(SnapshotFormatException::class.java)
        }
    }
}
