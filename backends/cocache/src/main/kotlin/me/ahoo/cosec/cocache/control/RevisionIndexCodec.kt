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
import me.ahoo.cosec.cocache.control.StoredJson.field
import me.ahoo.cosec.cocache.control.StoredJson.instant
import me.ahoo.cosec.cocache.control.StoredJson.items
import me.ahoo.cosec.cocache.control.StoredJson.long
import me.ahoo.cosec.cocache.control.StoredJson.malformed
import me.ahoo.cosec.cocache.control.StoredJson.only
import me.ahoo.cosec.cocache.control.StoredJson.read
import me.ahoo.cosec.cocache.control.StoredJson.text
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode

private fun JsonNode.digest(what: String): SnapshotDigest {
    val text = text(what)
    if (!SnapshotDigest.isDigest(text)) malformed(what, "[$text] is not a digest")
    return SnapshotDigest.parse(text)
}

/**
 * A [RevisionIndex] as JSON: `{"entries": [...], "pending": [...], "floor": 1}`, with `"reclaiming": [...]` only when
 * records are left to delete, so that an index with none carries no field a reader that does not know it would
 * refuse: the decoders of the stored formats reject unknown fields.
 */
internal object RevisionIndexCodec : Codec<RevisionIndex> {
    private const val WHAT = "revision index"

    override fun encode(value: RevisionIndex): ByteArray {
        val root = factory.objectNode()
        val entries = root.putArray("entries")
        value.entries.forEach {
            entries.addObject().put("revision", it.revision).put("digest", it.digest.toString())
                .put("publishedAt", it.publishedAt.toString())
        }
        val pending = root.putArray("pending")
        value.pending.forEach {
            pending.addObject().put(
                "digest",
                it.digest.toString()
            ).put("since", it.since.toString())
        }
        root.put("floor", value.floor)
        if (value.reclaiming.isNotEmpty()) {
            root.putArray("reclaiming").apply { value.reclaiming.forEach { add(it.toString()) } }
        }
        return CoSecJson.mapper.writeValueAsBytes(root)
    }

    override fun decode(bytes: ByteArray): RevisionIndex {
        val root = read(bytes, WHAT)
        root.only(WHAT, "entries", "pending", "floor", "reclaiming")
        return try {
            RevisionIndex(
                entries = root.field("entries", WHAT).items(WHAT).map { entry ->
                    entry.only(WHAT, "revision", "digest", "publishedAt")
                    RevisionEntry(
                        entry.field("revision", WHAT).long(WHAT),
                        entry.field("digest", WHAT).digest(WHAT),
                        entry.field("publishedAt", WHAT).instant(WHAT),
                    )
                },
                pending = root.field("pending", WHAT).items(WHAT).map { pending ->
                    pending.only(WHAT, "digest", "since")
                    PendingRecord(
                        pending.field("digest", WHAT).digest(WHAT),
                        pending.field("since", WHAT).instant(WHAT)
                    )
                },
                floor = root.field("floor", WHAT).long(WHAT),
                reclaiming = root.get("reclaiming")?.items(WHAT)?.map { it.digest(WHAT) }.orEmpty(),
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("The $WHAT is malformed: ${error.message}", error)
        }
    }
}
