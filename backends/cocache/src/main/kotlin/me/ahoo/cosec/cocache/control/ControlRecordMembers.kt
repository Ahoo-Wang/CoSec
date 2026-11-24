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

package me.ahoo.cosec.cocache.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredMembers
import me.ahoo.cosec.document.json.StoredMembers.Companion.optional
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredMembers.WhenAbsent
import me.ahoo.cosec.document.json.StoredRecord

/**
 * Every member of the control plane's revision index ([RevisionIndexCodec]) and app list ([AppListCodec]), `SNP-28`:
 * what its absence is read as, and whether ignoring or dropping it would widen anything. Each is read by every 8.x reader.
 */
internal object ControlRecordMembers {
    val REVISION_INDEX = StoredMembers(
        StoredRecord.REVISION_INDEX,
        StoredMembers.COMMON + listOf(
            required("entries"),
            required("entries.revision"),
            required("entries.digest"),
            required("entries.publishedAt"),
            // Entries written before the parent field: the chain is not checked through them (CTL-35), which grants nothing.
            optional("entries.parent", WhenAbsent.NONE),
            optional("entries.size", WhenAbsent.NONE),
            required("pending"),
            required("pending.digest"),
            required("pending.since"),
            required("floor"),
            // Records being reclaimed: lost, they are left in the store, which only keeps more.
            optional("reclaiming", WhenAbsent.EMPTY),
            // The references a publish indexed: lost, its settlement removes none, and a stale reference only blocks more.
            optional("indexed", WhenAbsent.EMPTY),
            required("indexed.target"),
            required("indexed.policy"),
            required("indexed.revision"),
            // Not the referrer's own definition: the referrer counts as binding the policy, which asks for more.
            optional("indexed.own", WhenAbsent.FALSE),
        ),
    )

    val APP_LIST = StoredMembers(
        StoredRecord.APP_LIST,
        StoredMembers.COMMON + listOf(required("apps"), required("complete")),
    )
}
