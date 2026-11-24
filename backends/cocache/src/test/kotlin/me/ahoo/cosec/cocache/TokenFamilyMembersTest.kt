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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.document.json.StoredRefusals
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * SNP-28 for the session record: its members registry and its codec name the same members, each absent member reads as
 * the registry says, and whether ignoring or dropping it widens is stated apart. SNP-27, the rewrite guard (RFC 0014 G5):
 * written by a later version, rewritten by an older reader, read again, a session is never wider.
 */
class TokenFamilyMembersTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val full = TokenFamily(
        "f1", TenantId("acme"), PrincipalId("alice"), at, at.plusSeconds(60), 3, at.plusSeconds(10), true, "root1",
    )
    private val members = TokenFamilyMembers.MEMBERS

    private fun tree(family: TokenFamily) = CoSecJson.mapper.readTree(TokenFamilyCodec.encode(family)) as ObjectNode

    private fun read(node: ObjectNode) = TokenFamilyCodec.decode(CoSecJson.mapper.writeValueAsBytes(node))

    @Test
    fun `the registry names every member the codec writes, and only those`() {
        members.record.assert().isEqualTo(StoredRecord.SESSION)
        val written = tree(full).propertyNames().toSet()
        // An 8.0 writer writes no crit: every member it writes is one every 8.x reader knows.
        members.members.map { it.path }.toSet().assert().isEqualTo(written + "crit")
    }

    @Test
    fun `each absent member reads as the registry says`() {
        for (member in members.members) {
            val node = tree(full).also { it.remove(member.path) }
            when (member.whenAbsent) {
                WhenAbsent.REFUSED -> assertThrows<SnapshotFormatException> { read(node) }
                WhenAbsent.NONE -> CoSecJson.mapper.readTree(TokenFamilyCodec.encode(read(node)))
                    .get(member.path).let { it == null || it.isNull }.assert().describedAs(member.path).isTrue()
                WhenAbsent.FIRST, WhenAbsent.EMPTY, WhenAbsent.FALSE, WhenAbsent.TRUE -> read(node)
            }
        }
    }

    /** The two ways an older reader can lose a member, asserted apart: ignoring it, and dropping it when it rewrites. */
    @Test
    fun `whether ignoring a member widens, and whether dropping it does, are each stated`() {
        members.members.filter { it.ignoringWidens }.map { it.path }.assert().containsExactly("root")
        members.members.filter { it.droppingWidens }.map { it.path }.assert().containsExactly("root")
        // root, in the 8.0 baseline, is guarded by its format: a reader of format 1 refuses a rooted session.
        tree(full)[StoredFormat.FIELD].intValue().assert().isEqualTo(2)
    }

    /** SNP-29: 8.x reads a session of format 2 at most; a format above is a later major version's, refused as later. */
    @Test
    fun `the session's format numbers are frozen for 8_x`() {
        read(tree(full)).assert().isEqualTo(full)
        val refusal = assertThrows<SnapshotFormatException> { read(tree(full).put(StoredFormat.FIELD, 3)) }
        StoredFormat.later(refusal).assert().isNotNull()
    }

    /** A reader that knows only [known] of a record's members, as an earlier release would: it rewrites what it read. */
    private fun olderRewrite(node: ObjectNode, known: Set<String>): ObjectNode {
        StoredFormat.read(node, members, "token family", known = 2)
        val kept = node.deepCopy()
        node.propertyNames().filter { it !in known }.forEach { kept.remove(it) }
        return kept
    }

    @Test
    fun `a later member crit names is refused by an older reader, which never rewrites it`() {
        val later = tree(full).put("device", "d1").also { it.putArray("crit").add("device") }
        val written = CoSecJson.mapper.writeValueAsBytes(later)
        val before = StoredRefusals.count(StoredRecord.SESSION, StoredRefusals.Reason.LATER)
        val refusal = assertThrows<SnapshotFormatException> { olderRewrite(later, members.byPath.keys) }
        StoredFormat.later(refusal)?.members.assert().containsExactly("device")
        StoredRefusals.count(StoredRecord.SESSION, StoredRefusals.Reason.LATER).assert().isEqualTo(before + 1)
        // Nothing read, so nothing written back: the record stays as the later version wrote it.
        CoSecJson.mapper.writeValueAsBytes(later).assert().isEqualTo(written)
    }

    @Test
    fun `a later member without crit is dropped by an older reader, and the session it leaves is the same session`() {
        val later = tree(full).put("hint", "only a later version reads it")
        val rewritten = olderRewrite(later, members.byPath.keys)
        rewritten.has("hint").assert().isFalse()
        read(rewritten).assert().isEqualTo(full)
    }

    @Test
    fun `every member an 8_0 reader knows survives its rewrite`() {
        val node = tree(full)
        read(olderRewrite(node, members.byPath.keys)).assert().isEqualTo(full)
    }
}
