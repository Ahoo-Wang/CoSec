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

/**
 * Every member of one kind of stored [record], by its path from the record's root (`SNP-28`): what a reader takes its
 * absence for, whether a reader that ignored it would decide more widely, and whether a reader that dropped it when it
 * rewrote the record would leave it wider. The one record of all three for that kind; its codec writes exactly these
 * members, which a test holds it to.
 *
 * A record is read, changed and written back by whichever version holds it, so a member an older reader does not know
 * is not only ignored but lost when that reader rewrites the record ([droppingWidens]): the session would stay as the
 * older version left it after every instance is upgraded (RFC 0014 G5). A member added after 8.0 whose being ignored
 * or dropped would widen anything is written with its record's `crit` ([StoredCrit]): an older reader refuses that one
 * record instead, and never rewrites it.
 */
@InternalCoSecApi
class StoredMembers(val record: StoredRecord, val members: List<Member>) {
    /** What a reader takes a member's absence for. */
    @InternalCoSecApi
    enum class WhenAbsent {
        /** The record does not read: refused as malformed. */
        REFUSED,

        /** Read as none. */
        NONE,

        /** Read as false. */
        FALSE,

        /** Read as true. */
        TRUE,

        /** Read as empty. */
        EMPTY,

        /** The format before formats: 1. */
        FIRST,
    }

    /**
     * One member: its [path] (names joined by `.`; through an array, each element's member), what its absence is read
     * as, and whether ignoring it, or dropping it in a rewrite, would widen what is decided or kept. An [opaque] member
     * holds what this rule does not read into: a map whose keys are data, or a value read strictly by its own format (a
     * document, an operation); a path below it is no member.
     */
    @InternalCoSecApi
    class Member(
        val path: String,
        val whenAbsent: WhenAbsent,
        val ignoringWidens: Boolean,
        val droppingWidens: Boolean,
        val opaque: Boolean = false,
    )

    /** [members] by path. */
    val byPath: Map<String, Member> = members.associateBy { it.path }

    init {
        require(byPath.size == members.size) { "The members of a ${record.tag} record are each listed once." }
    }

    @InternalCoSecApi
    companion object {
        /** A member every record holds or refuses to read without: neither ignored nor dropped by a reader of 8.x. */
        fun required(path: String) = Member(path, WhenAbsent.REFUSED, ignoringWidens = false, droppingWidens = false)

        /** A [required] member whose value this rule does not read into ([Member.opaque]). */
        fun opaque(path: String) =
            Member(path, WhenAbsent.REFUSED, ignoringWidens = false, droppingWidens = false, opaque = true)

        /** An optional member whose absence ([whenAbsent]) narrows, or changes nothing a decision reads. */
        fun optional(path: String, whenAbsent: WhenAbsent) =
            Member(path, whenAbsent, ignoringWidens = false, droppingWidens = false)

        /** The members every record has: its format, and the members it must be understood by. */
        val COMMON: List<Member> = listOf(
            Member(StoredFormat.FIELD, WhenAbsent.FIRST, ignoringWidens = false, droppingWidens = false),
            Member(StoredCrit.MEMBER, WhenAbsent.EMPTY, ignoringWidens = false, droppingWidens = false),
        )
    }
}
