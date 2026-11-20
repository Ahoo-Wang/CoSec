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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * UC-14 review: the diff names its sections by hand, so a member added to [PolicyDocument] later must find a place in
 * it, or two revisions that differ only in it would diff as nothing, and a reviewer approve what the diff did not show.
 */
class DiffMembersTest {
    private val members: List<String> = PolicyDocument::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .map { it.name }

    /** The members the sections hold, a member once for each section that holds it. */
    private val sections = DocumentReads.SECTIONS.flatMap { it.members }

    @Test
    fun `every member of a document is a header, an entry list or a section of the diff`() {
        members.assert().isNotEmpty()
        for (member in members) {
            val homes = listOf(member in DocumentReads.HEADER, member in DocumentReads.ENTRIES) +
                List(sections.count { it == member }) { true }
            homes.count { it }.assert().describedAs(
                "PolicyDocument.$member is in ${homes.count { it }} of DocumentReads' HEADER, ENTRIES and SECTIONS, " +
                    "not exactly one: a member the diff does not place is never compared, so a revision that changes " +
                    "only it diffs as nothing. Add it to DocumentReads.SECTIONS (a section of its own, cleared and " +
                    "taken alone), or to HEADER or ENTRIES if it is one of those."
            ).isEqualTo(1)
        }
    }

    @Test
    fun `the diff places no member a document does not have`() {
        (DocumentReads.HEADER + DocumentReads.ENTRIES + sections).filterNot { it in members }.assert()
            .describedAs("names in DocumentReads that are no member of PolicyDocument").isEmpty()
    }
}
