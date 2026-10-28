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

    private fun <T> pinned(name: String, value: T, encode: (T) -> ByteArray, decode: (ByteArray) -> T, expected: String) {
        val bytes = encode(value)
        bytes.decodeToString().assert().describedAs(name).isEqualTo(expected)
        encode(decode(expected.encodeToByteArray())).decodeToString().assert().describedAs(name).isEqualTo(expected)
    }

    @Test
    fun `api keys`() {
        val key = ApiKey("k1", "alice", "acme", setOf("admin"), setOf("p1"), mapOf("team" to "a"), at, true)
        pinned(
            "apiKey",
            key,
            { CoSecJson.mapper.writeValueAsBytes(it) },
            { CoSecJson.mapper.readValue(it, ApiKey::class.java) },
            """{"id":"k1","principalId":"alice","tenantId":"acme","roles":["admin"],"policies":["p1"],"attributes":{"team":"a"},"expiresAt":"2026-11-01T00:00:00Z","enabled":true}""",
        )
    }

    @Test
    fun memberships() {
        val memberships = Memberships(
            Memberships.Source.PROVIDER,
            mapOf(SpaceId.DEFAULT to setOf(RoleId("admin")), SpaceId("sales") to setOf(RoleId("viewer"))),
            listOf(PolicyId("p1")),
        )
        pinned("memberships", memberships, MembershipsCodec::encode, MembershipsCodec::decode, """{"roles":{"":["admin"],"sales":["viewer"]},"attachments":["p1"]}""")
    }

    @Test
    fun `snapshot documents`() {
        pinned("snapshot", document, SnapshotDocumentCodec::encode, SnapshotDocumentCodec::decode, """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""")
    }

    @Test
    fun `control records`() {
        val index = RevisionIndex(listOf(RevisionEntry(3, digest, at)), listOf(PendingRecord(digest, at)), floor = 2)
        pinned("revisionIndex", index, RevisionIndexCodec::encode, RevisionIndexCodec::decode, """{"entries":[{"revision":3,"digest":"000102030405060708090a0b0c0d0e0f10111213","publishedAt":"2026-11-01T00:00:00Z"}],"pending":[{"digest":"000102030405060708090a0b0c0d0e0f10111213","since":"2026-11-01T00:00:00Z"}],"floor":2}""")
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
        pinned("changeRequest", change, ChangeRequestCacheCodec::encode, ChangeRequestCacheCodec::decode, """{"id":"c1","key":"tenant:acme","base":{"revision":0},"operations":[],"author":{"tenant":"acme","principal":"alice"},"note":"first","kind":"PUBLISH","state":"DRAFT","findings":[],"checkedWith":{},"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}""")
        pinned("stageSlot", StageSlot(ChangeId("c1"), at), StageSlotCodec::encode, StageSlotCodec::decode, """{"holder":"c1","since":"2026-11-01T00:00:00Z"}""")
        val keys = setOf(SnapshotKey.Tenant("b"), SnapshotKey.Platform, SnapshotKey.App("order"))
        pinned("keys", keys, SnapshotKeySetCodec::encode, SnapshotKeySetCodec::decode, """["app:order","platform","tenant:b"]""")
        pinned("listed", mapOf("tenant:acme" to at), ListedCodec::encode, ListedCodec::decode, """{"tenant:acme":"2026-11-01T00:00:00Z"}""")
    }

    @Test
    fun `shadow records`() {
        pinned("candidate", StoredCandidate("c1@1", 4, document), StoredCandidateCodec::encode, StoredCandidateCodec::decode, """{"window":"c1@1","baseRevision":4,"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","document":{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}""")
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
        pinned("report", report, ShadowReportCodec::encode, ShadowReportCodec::decode, """{"window":"c1@1","instance":"i1","counts":{"SAME":3,"NARROWED":1},"samples":{"NARROWED":[{"tenant":"acme","principal":"bob","app":"order","space":"","action":"http:GET","resource":"http//orders/1","enforcedBy":"read[0]","candidateBy":null}]},"baseRevisions":[4],"first":"2026-11-01T00:00:00Z","last":"2026-11-01T00:00:00Z"}""")
        pinned("reporters", setOf("i2", "i1"), ReportersCodec::encode, ReportersCodec::decode, """["i1","i2"]""")
    }
}
