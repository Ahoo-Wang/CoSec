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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.StoredJson.factory
import me.ahoo.cosec.cocache.control.StoredJson.long
import me.ahoo.cosec.cocache.control.StoredJson.malformed
import me.ahoo.cosec.cocache.control.StoredJson.read
import me.ahoo.cosec.cocache.control.StoredJson.text
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The referrers of one policy as a JSON object of their encodings, sorted, each with the revision of it that last
 * indexed the reference (#1207): `{"tenant:acme": 3}`. Equal referrers have equal bytes and digests.
 */
internal object ReferrersCodec : Codec<Map<SnapshotKey, Long>> {
    private const val WHAT = "back-reference set"

    override fun encode(value: Map<SnapshotKey, Long>): ByteArray {
        val node = factory.objectNode()
        value.entries.map { (key, revision) -> SnapshotKeyEncoder.encode(key) to revision }.sortedBy { it.first }
            .forEach { (key, revision) -> node.put(key, revision) }
        return CoSecJson.mapper.writeValueAsBytes(node)
    }

    /** Anything but the object form, a member that is not a document key, or a negative revision, is malformed. */
    override fun decode(bytes: ByteArray): Map<SnapshotKey, Long> {
        val root = read(bytes, WHAT)
        if (!root.isObject) malformed(WHAT, "$root is not an object")
        val referrers = LinkedHashMap<SnapshotKey, Long>()
        for ((text, revision) in root.properties()) {
            val value = revision.long(WHAT)
            if (value < 0) malformed(WHAT, "revision $value of [$text] is negative")
            // A document of an id no longer valid (`/`, #1403) is read by nothing, so its reference holds nothing back.
            val key = SnapshotKeyEncoder.decode(text)
                ?: if (SnapshotKeyEncoder.retired(text)) continue else malformed(WHAT, "[$text] is not a document key")
            referrers[key] = value
        }
        return referrers
    }
}
