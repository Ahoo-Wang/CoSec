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
import me.ahoo.cosec.document.json.StoredMembers
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredMembers.Member
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord

/**
 * Every member of a session record ([TokenFamilyCodec], `SNP-28`): what its absence is read as, and whether ignoring it
 * or dropping it in a rewrite would widen what a session may do. Each is read by every 8.x reader.
 */
internal object TokenFamilyMembers {
    val MEMBERS = StoredMembers(
        StoredRecord.SESSION,
        StoredMembers.COMMON + listOf(
            required("id"),
            required("tenant"),
            required("principal"),
            required("authTime"),
            required("expiresAt"),
            required("generation"),
            // Never rotated: the pair just replaced has no leeway to be answered in, which narrows.
            Member("rotatedAt", WhenAbsent.NONE, ignoringWidens = false, droppingWidens = false),
            required("revoked"),
            // A switched session lives only while its login does (IDN-36): ignored or dropped, it would outlive it. In the
            // 8.0 baseline, written as format 2, which no reader before it read (IDN-31).
            Member("root", WhenAbsent.NONE, ignoringWidens = true, droppingWidens = true),
        ),
    )
}
