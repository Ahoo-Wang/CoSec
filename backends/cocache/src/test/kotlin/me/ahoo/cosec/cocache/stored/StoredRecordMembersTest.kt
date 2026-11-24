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

package me.ahoo.cosec.cocache.stored

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.document.json.StoredRefusals
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * SNP-28 for the control plane's and the shadow window's records: each registry and its codec name the same members,
 * each absent member reads as the registry says, and whether ignoring or dropping a member widens is stated apart.
 * SNP-27 and SNP-29 for each: a member `crit` names that this version does not know, or a later format, refuses that one
 * record, which is never rewritten; a later member without `crit` is dropped, leaving the same record.
 */
class StoredRecordMembersTest {
    private val cases = StoredRecordCases.ALL

    @Test
    fun `every kind of record has a registry, and each registry names every member its codec writes, and only those`() {
        cases.map { it.members.record }.plus(StoredRecord.SESSION).assert()
            .containsExactlyInAnyOrder(*StoredRecord.entries.toTypedArray())
        for (case in cases) {
            // An 8.0 writer writes no crit: every member it writes is one every 8.x reader knows.
            case.paths().plus(CRIT).assert().describedAs(case.tag)
                .containsExactlyInAnyOrderElementsOf(case.members.byPath.keys)
            case.rewritten(case.full()).assert().describedAs(case.tag).isEqualTo(case.full())
        }
    }

    @Test
    fun `each absent member reads as the registry says`() {
        for (case in cases) {
            for (member in case.members.members) {
                val what = "${case.tag}: ${member.path}"
                val node = case.without(member.path)
                if (member.whenAbsent == WhenAbsent.REFUSED) {
                    assertThrows<SnapshotFormatException>(what) { case.rewritten(node) }
                    continue
                }
                val read = try {
                    case.rewritten(node)
                } catch (error: SnapshotFormatException) {
                    throw AssertionError("$what is absent and does not read", error)
                }
                // None is written as nothing, or as null.
                if (member.whenAbsent == WhenAbsent.NONE) {
                    case.values(read, member.path).filterNot { it.isNull }.assert().describedAs(what).isEmpty()
                }
            }
        }
    }

    /** The two ways an older reader can lose a member, asserted apart: ignoring it, and dropping it when it rewrites. */
    @Test
    fun `whether ignoring a member widens, and whether dropping it does, are each stated`() {
        val ignoring = cases.flatMap { case -> case.members.members.filter {
            it.ignoringWidens
        }.map { "${case.tag}:${it.path}" } }
        val dropping = cases.flatMap { case -> case.members.members.filter {
            it.droppingWidens
        }.map { "${case.tag}:${it.path}" } }
        ignoring.assert().containsExactly(
            "change:base.digest",
            "change:escalations",
            "staging_slot:holder",
            "instance_status:shadows"
        )
        dropping.assert().containsExactly(
            "change:base.digest",
            "change:escalations",
            "staging_slot:holder",
            "instance_status:shadows"
        )
    }

    @Test
    fun `a later member crit names refuses that record as a later version's, which is never rewritten`() {
        for (case in cases) {
            val later = case.withLater(critical = true)
            val before = StoredRefusals.count(case.members.record, StoredRefusals.Reason.LATER)
            val refusal = assertThrows<SnapshotFormatException>(case.tag) { case.rewritten(later) }
            StoredFormat.later(refusal)?.members.assert().describedAs(case.tag).containsExactly("later")
            StoredFormat.later(refusal)?.record.assert().describedAs(case.tag).isEqualTo(case.members.record)
            StoredRefusals.count(case.members.record, StoredRefusals.Reason.LATER).assert().describedAs(case.tag)
                .isEqualTo(before + 1)
            // Nothing read, so nothing written back: the record stays as the later version wrote it.
            later.assert().describedAs(case.tag).isEqualTo(case.withLater(critical = true))
        }
    }

    @Test
    fun `a later member without crit is dropped by a rewrite, which leaves the same record`() {
        for (case in cases) {
            case.rewritten(case.withLater(critical = false)).assert().describedAs(case.tag).isEqualTo(case.full())
        }
    }

    /** SNP-29: 8.x reads each of these records at format 1; a format above is a later major version's. */
    @Test
    fun `the records' format numbers are frozen for 8_x`() {
        for (case in cases) {
            case.full()[StoredFormat.FIELD].intValue().assert().describedAs(case.tag).isEqualTo(1)
            val refusal = assertThrows<SnapshotFormatException>(case.tag) {
                case.rewritten(case.full().put(StoredFormat.FIELD, 2))
            }
            StoredFormat.later(refusal).assert().describedAs(case.tag).isNotNull()
        }
    }
}
