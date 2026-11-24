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

package me.ahoo.cosec.document.json

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredMembers.Member
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SNP-27: a stored record's `crit` names the members a reader must understand. One this version does not know refuses
 * that record as a later version's, never read; a list that is not one is malformed; each refusal by the reader's rule
 * is counted by kind and reason (OBS-12).
 */
class StoredCritTest {
    private val members = StoredMembers(
        StoredRecord.CHANGE,
        StoredMembers.COMMON + listOf(
            required("id"),
            Member("approvals", WhenAbsent.EMPTY, ignoringWidens = false, droppingWidens = false),
            Member("approvals.via", WhenAbsent.NONE, ignoringWidens = true, droppingWidens = true),
        ),
    )

    private fun read(json: String) = StoredFormat.read(CoSecJson.mapper.readTree(json), members, "record")

    private fun refused(json: String) = assertThrows<SnapshotFormatException> { read(json) }

    private fun counted(reason: StoredRefusals.Reason) = StoredRefusals.count(StoredRecord.CHANGE, reason)

    @Test
    fun `a record without crit, or with an empty one, reads as before`() {
        read("""{"id":"c1","future":1}""").assert().isEqualTo(1)
        read("""{"format":1,"id":"c1","crit":[]}""").assert().isEqualTo(1)
    }

    @Test
    fun `a member crit names that this version knows is read, through an array's elements too`() {
        read("""{"id":"c1","approvals":[{"via":"x"},{}],"crit":["approvals.via","id"]}""").assert().isEqualTo(1)
    }

    @Test
    fun `a member crit names that this version does not know refuses the record as a later version's, and counts it`() {
        val before = counted(StoredRefusals.Reason.LATER)
        val refusal = refused("""{"id":"c1","device":"d1","approvals":[],"crit":["device","approvals.reason"]}""")
        refusal.message.assert().contains("[device], [approvals.reason]").contains("a later CoSec wrote it")
        val later = StoredFormat.later(refusal)
        later?.record.assert().isEqualTo(StoredRecord.CHANGE)
        later?.members.assert().containsExactly("device", "approvals.reason")
        counted(StoredRefusals.Reason.LATER).assert().isEqualTo(before + 1)
    }

    @Test
    fun `a later format is a later version's too, and counts as one`() {
        val before = counted(StoredRefusals.Reason.LATER)
        StoredFormat.later(refused("""{"format":2,"id":"c1"}""")).assert().isNotNull()
        counted(StoredRefusals.Reason.LATER).assert().isEqualTo(before + 1)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"id":"c1","crit":"id"}""",
            """{"id":"c1","crit":[1]}""",
            """{"id":"c1","crit":["a..b"]}""",
            """{"id":"c1","crit":["1a"]}""",
            """{"id":"c1","crit":["id","id"]}""",
            """{"id":"c1","crit":["approvals"]}""",
            """{"id":"c1","approvals":[{}],"crit":["approvals.via"]}""",
            """{"id":"c1","crit":["a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q"]}""",
            """{"format":0,"id":"c1"}""",
            """[]""",
        ]
    )
    fun `a crit that is not one, or a record that is not one, is malformed, and counts as malformed`(json: String) {
        val before = counted(StoredRefusals.Reason.MALFORMED)
        val refusal = refused(json)
        StoredFormat.later(refusal).assert().isNull()
        counted(StoredRefusals.Reason.MALFORMED).assert().isEqualTo(before + 1)
    }

    @Test
    fun `a path is printed printably, and a member is listed once`() {
        refused("""{"id":"c1","crit":["dev"]}""").message.assert().contains("[dev]")
        assertThrows<IllegalArgumentException> { StoredMembers(StoredRecord.CHANGE, listOf(required("a"), required("a"))) }
    }
}
