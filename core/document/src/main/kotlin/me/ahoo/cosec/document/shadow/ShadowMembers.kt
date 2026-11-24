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

package me.ahoo.cosec.document.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredMembers
import me.ahoo.cosec.document.json.StoredMembers.Companion.opaque
import me.ahoo.cosec.document.json.StoredMembers.Companion.optional
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredMembers.Member
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord

/**
 * Every member of the shadow window's records [ShadowCodec] writes (`SNP-28`): what its absence is read as, and whether
 * ignoring it, or dropping it in a rewrite, would widen what a window decides. Each is read by every 8.x reader.
 */
@InternalCoSecApi
object ShadowMembers {
    val CANDIDATE = StoredMembers(
        StoredRecord.CANDIDATE,
        StoredMembers.COMMON + listOf(required("window"), required("baseRevision"), opaque("document")),
    )

    val SHADOW_REPORT = StoredMembers(
        StoredRecord.SHADOW_REPORT,
        StoredMembers.COMMON + listOf(
            required("window"),
            required("instance"),
            opaque("counts"),
            opaque("samples"),
            required("baseRevisions"),
            optional("first", WhenAbsent.NONE),
            optional("last", WhenAbsent.NONE),
        ),
    )

    /** One instance's status, the object under its id. */
    val INSTANCE_STATUS = StoredMembers(
        StoredRecord.INSTANCE_STATUS,
        StoredMembers.COMMON + listOf(
            required("at"),
            required("formatLevel"),
            // Acknowledges no window: no window passes on it, which narrows.
            optional("windows", WhenAbsent.NONE),
            // An instance that does not shadow, ignored or dropped, would count as one that evaluated the window.
            Member("shadows", WhenAbsent.TRUE, ignoringWidens = true, droppingWidens = true),
        ),
    )

    val RESIDENT_DOCUMENTS = StoredMembers(
        StoredRecord.RESIDENT_DOCUMENTS,
        StoredMembers.COMMON + listOf(required("instance"), required("at"), opaque("documents")),
    )
}
