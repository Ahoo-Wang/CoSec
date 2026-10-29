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

package me.ahoo.cosec.cache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.control.ChangeRequestCacheCodec
import me.ahoo.cosec.cache.control.ListedCodec
import me.ahoo.cosec.cache.control.RevisionIndexCodec
import me.ahoo.cosec.cache.control.SnapshotKeySetCodec
import me.ahoo.cosec.cache.control.StageSlotCodec
import me.ahoo.cosec.cache.shadow.ReportersCodec
import me.ahoo.cosec.cache.shadow.ShadowReportCodec
import me.ahoo.cosec.cache.shadow.StoredCandidateCodec
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowSample
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * The bytes CoSec keeps in Redis, pinned: each stored type is written exactly as before and reads back. One mapper
 * ([CoSecJson]) writes them all (RFC 0011 D3), so a change to its configuration, a Jackson upgrade or a codec change
 * that alters stored bytes fails here instead of in a running cluster.
 */
class StoredFormatTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val digest = SnapshotDigest(ByteArray(20) { it.toByte() })
    private val document = SnapshotDocument(
        2,
        5,
        at,
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray(),
    )

    /**
     * The bytes `main` wrote before RFC 0011 PR-2, kept in `stored-format/<name>.json` with no trailing newline (except
     * `apiKey`, `apiKey-uncapped` and `memberships-inactive`, the formats of RFC 0009 PR-3): each was
     * produced by running that commit's writers (its `CoSecJsonSerializer` and codecs, the snapshot through its
     * `SnapshotDocumentCodec`) on the values below, in a worktree of `origin/main` at da58e780.
     */
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/stored-format/$name.json")) { "No fixture $name." }.readText()

    private fun <T> pinned(name: String, value: T, encode: (T) -> ByteArray, decode: (ByteArray) -> T, expected: String) {
        val bytes = encode(value)
        bytes.decodeToString().assert().describedAs(name).isEqualTo(expected)
        encode(decode(expected.encodeToByteArray())).decodeToString().assert().describedAs(name).isEqualTo(expected)
    }

    /** RFC 0009 §4.1: written by this format's [ApiKeyCodec], not by `main` before it. */
    @Test
    fun `api keys`() {
        val capped = ApiKey("k1", "alice", "acme", linkedSetOf("admin", "viewer@sales"), at, true)
        pinned(
            "apiKey",
            capped,
            { ApiKeyCodec.encode(it).encodeToByteArray() },
            { ApiKeyCodec.decode(it.decodeToString()) },
            fixture("apiKey")
        )
        val uncapped = ApiKey("k2", "svc", "acme", null, null, false)
        pinned(
            "apiKey-uncapped",
            uncapped,
            { ApiKeyCodec.encode(it).encodeToByteArray() },
            { ApiKeyCodec.decode(it.decodeToString()) },
            fixture("apiKey-uncapped"),
        )
        ApiKeyCodec.decode(fixture("apiKey-uncapped")).roles.assert().isNull()
    }

    /**
     * A key `main` wrote before RFC 0009 (the bytes of da58e780) is refused, never read as a key without a cap, which
     * would give it everything its principal holds; the message names the migration.
     */
    @Test
    fun `api keys before 8 are refused`() {
        val refused = assertThrows<IllegalArgumentException> { ApiKeyCodec.decode(fixture("apiKey-before-8")) }
        refused.message.assert().contains("cosec-migrate api-keys")
    }

    @Test
    fun `a stored api key has exactly its fields`() {
        val written = ApiKeyCodec.encode(ApiKey("k1", "alice", "acme"))
        assertThrows<IllegalArgumentException> { ApiKeyCodec.decode(written.replace("\"format\":2", "\"format\":3")) }
        assertThrows<IllegalArgumentException> { ApiKeyCodec.decode(written.replace("\"roles\":null,", "")) }
        assertThrows<IllegalArgumentException> {
            ApiKeyCodec.decode(written.replace("\"enabled\":true", "\"enabled\":true,\"policies\":[\"p1\"]"))
        }
        assertThrows<IllegalArgumentException> { ApiKeyCodec.decode(written.replace("\"acme\"", "\"\"")) }
    }

    @Test
    fun memberships() {
        val memberships = Memberships(
            Memberships.Source.PROVIDER,
            mapOf(SpaceId.DEFAULT to setOf(RoleId("admin")), SpaceId("sales") to setOf(RoleId("viewer"))),
            listOf(PolicyId("p1")),
        )
        pinned(
            "memberships",
            memberships,
            MembershipsCodec::encode,
            MembershipsCodec::decode,
            fixture("memberships")
        )
        MembershipsCodec.decode(fixture("memberships").encodeToByteArray()).active.assert().isTrue()
    }

    /** RFC 0009 §4.2: only a deactivated principal writes `active`, so every entry cached before it reads as active. */
    @Test
    fun `inactive memberships`() {
        val inactive = Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList(), active = false)
        pinned(
            "memberships-inactive",
            inactive,
            MembershipsCodec::encode,
            MembershipsCodec::decode,
            fixture("memberships-inactive")
        )
        MembershipsCodec.decode(fixture("memberships-inactive").encodeToByteArray()).active.assert().isFalse()
        assertThrows<IllegalArgumentException> {
            MembershipsCodec.decode("""{"roles":{},"attachments":[],"active":true}""".encodeToByteArray())
        }
    }

    @Test
    fun `snapshot documents`() {
        pinned(
            "snapshot",
            document,
            SnapshotDocumentCodec::encode,
            SnapshotDocumentCodec::decode,
            fixture("snapshot")
        )
    }

    @Test
    fun `control records`() {
        val index = RevisionIndex(listOf(RevisionEntry(3, digest, at)), listOf(PendingRecord(digest, at)), floor = 2)
        pinned(
            "revisionIndex",
            index,
            RevisionIndexCodec::encode,
            RevisionIndexCodec::decode,
            fixture("revisionIndex")
        )
        val change = ChangeRequest(
            id = ChangeId("c1"),
            key = SnapshotKey.Tenant("acme"),
            base = Base(0, null),
            operations = emptyList(),
            author = Actor(TenantId("acme"), PrincipalId("alice")),
            note = "first",
            kind = ProvenanceKind.PUBLISH,
            state = ChangeState.DRAFT,
            findings = emptyList(),
            candidate = null,
            checkedWith = emptyMap(),
            createdAt = at,
            updatedAt = at,
        )
        pinned(
            "changeRequest",
            change,
            ChangeRequestCacheCodec::encode,
            ChangeRequestCacheCodec::decode,
            fixture("changeRequest")
        )
        pinned(
            "stageSlot",
            StageSlot(ChangeId("c1"), at),
            StageSlotCodec::encode,
            StageSlotCodec::decode,
            fixture("stageSlot")
        )
        val keys = setOf(SnapshotKey.Tenant("b"), SnapshotKey.Platform, SnapshotKey.App("order"))
        pinned(
            "keys",
            keys,
            SnapshotKeySetCodec::encode,
            SnapshotKeySetCodec::decode,
            fixture("keys")
        )
        pinned(
            "listed",
            mapOf("tenant:acme" to at),
            ListedCodec::encode,
            ListedCodec::decode,
            fixture("listed")
        )
    }

    @Test
    fun `shadow records`() {
        pinned(
            "candidate",
            StoredCandidate("c1@1", 4, document),
            StoredCandidateCodec::encode,
            StoredCandidateCodec::decode,
            fixture("candidate")
        )
        val report = ShadowReport(
            window = "c1@1",
            instance = "i1",
            counts = mapOf(ShadowOutcome.SAME to 3L, ShadowOutcome.NARROWED to 1L),
            samples = mapOf(
                ShadowOutcome.NARROWED to listOf(
                    ShadowSample("acme", "bob", "order", "", "http:GET", "http//orders/1", "read[0]", null),
                ),
            ),
            baseRevisions = setOf(4L),
            first = at,
            last = at,
        )
        pinned(
            "report",
            report,
            ShadowReportCodec::encode,
            ShadowReportCodec::decode,
            fixture("report")
        )
        pinned("reporters", setOf("i2", "i1"), ReportersCodec::encode, ReportersCodec::decode, fixture("reporters"))
    }
}
