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

package me.ahoo.cosec.cache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/** A revision record: a document's digest under its document's key (RFC 0007 §5.2). */
data class RevisionRecordKey(val document: SnapshotKey, val digest: SnapshotDigest)

/** The referrers of one policy of one document (RFC 0007 §5.8). */
data class ReferenceKey(val target: SnapshotKey, val policy: PolicyId)

/**
 * The caches of `CoCacheControlStore` (RFC 0007 §5.2), next to the snapshot heads of [CoSecCacheSpecs.policySnapshot]:
 *
 * | Cache | Redis key |
 * |---|---|
 * | revision records | `<p>:control:revision:<docKey>:<digest>` |
 * | revision index | `<p>:control:revisions:<docKey>` |
 * | back-references | `<p>:control:refs:<docKey>:<policyId>` |
 * | leases | `<p>:control:lease:<name>` |
 * | lists | `<p>:control:list:documents`, `<p>:control:list:open-changes` |
 * | change requests | `<p>:control:change:<changeId>` |
 * | staging slots | `<p>:control:stage:<docKey>` |
 *
 * All of them are [storeOfRecord] (`ttl = null`, failed reads throw), applied last: records and indexes are not data-plane
 * policy data, but reading a failure as "absent" would let the control plane publish on a wrong base (RFC 0007 §5.2).
 * Leases expire through the lifetime of each write instead. Keys and values are encoded explicitly, without reflection.
 */
object ControlCacheSpecs {
    fun revisionRecord(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
    ): CacheSpec<RevisionRecordKey, SnapshotDocument> =
        CacheSpec.builder<RevisionRecordKey, SnapshotDocument>(CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(RevisionRecordKeyEncoder)
            .codec(SnapshotDocumentCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun revisionIndex(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
    ): CacheSpec<SnapshotKey, RevisionIndex> =
        CacheSpec.builder<SnapshotKey, RevisionIndex>(CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(RevisionIndexCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun referenceIndex(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
    ): CacheSpec<ReferenceKey, Set<SnapshotKey>> =
        CacheSpec.builder<ReferenceKey, Set<SnapshotKey>>(CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(ReferenceKeyEncoder)
            .codec(SnapshotKeySetCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun changeRequest(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
    ): CacheSpec<ChangeId, ChangeRequest> =
        CacheSpec.builder<ChangeId, ChangeRequest>(CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it.value }
            .codec(ChangeRequestCacheCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun stage(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<SnapshotKey, StageSlot> =
        CacheSpec.builder<SnapshotKey, StageSlot>(CoSecCacheSpecs.STAGE_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.STAGE_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(StageSlotCodec)
            .policy(policy.storeOfRecord())
            .build()

    /** What [CoCacheControlStore.documents] and [CoCacheControlStore.openChanges] read: each entry with when it was listed. */
    fun list(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<String, Map<String, Instant>> =
        CacheSpec.builder<String, Map<String, Instant>>(CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(ListedCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun lease(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<String, String> =
        CacheSpec.builder<String, String>(CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(StringCodec)
            .policy(policy.storeOfRecord())
            .build()
}

/** `<docKey>:<digest>`. Document keys never contain a second `:`, so the encoding is injective. */
object RevisionRecordKeyEncoder : KeyEncoder<RevisionRecordKey> {
    override fun encode(key: RevisionRecordKey): String = "${SnapshotKeyEncoder.encode(key.document)}:${key.digest}"
}

/** `<docKey>:<policyId>`: the document key ends before the first `:` after its kind, so policy ids may contain `:`. */
object ReferenceKeyEncoder : KeyEncoder<ReferenceKey> {
    override fun encode(key: ReferenceKey): String = "${SnapshotKeyEncoder.encode(key.target)}:${key.policy.value}"
}

private val factory = JsonNodeFactory.instance
private val reader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

private fun read(bytes: ByteArray, what: String): JsonNode = try {
    reader.readTree(bytes) ?: throw SnapshotFormatException("The $what is empty.")
} catch (error: JacksonException) {
    throw SnapshotFormatException("The $what is not JSON: ${error.originalMessage}", error)
}

private fun malformed(what: String, reason: String): Nothing = throw SnapshotFormatException(
    "The $what is malformed: $reason."
)

private fun JsonNode.field(name: String, what: String): JsonNode = get(name) ?: malformed(what, "[$name] is missing")

private fun JsonNode.text(what: String): String = if (isString) asString() else malformed(what, "$this is not a string")

private fun JsonNode.instant(what: String): Instant = try {
    Instant.parse(text(what))
} catch (_: DateTimeParseException) {
    malformed(what, "$this is not an instant")
}

private fun JsonNode.digest(what: String): SnapshotDigest {
    val text = text(what)
    if (!DIGEST.matches(text)) malformed(what, "[$text] is not a digest")
    return SnapshotDigest(ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() })
}

private fun JsonNode.long(what: String): Long =
    if (isIntegralNumber && canConvertToLong()) asLong() else malformed(what, "$this is not an integer")

private fun JsonNode.items(what: String): List<JsonNode> = if (isArray) {
    values().toList()
} else {
    malformed(
        what,
        "$this is not an array"
    )
}

private fun JsonNode.only(what: String, vararg names: String) {
    if (!isObject) malformed(what, "$this is not an object")
    val unknown = properties().map { it.key }.toSet() - names.toSet()
    if (unknown.isNotEmpty()) malformed(what, "unknown fields $unknown")
}

private val DIGEST = Regex("^[0-9a-f]{40}$")
private const val HEX = 16

/** A [RevisionIndex] as JSON: `{"entries": [...], "pending": [...], "floor": 1}`. */
object RevisionIndexCodec : Codec<RevisionIndex> {
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
        return CoSecJsonSerializer.writeValueAsBytes(root)
    }

    override fun decode(bytes: ByteArray): RevisionIndex {
        val root = read(bytes, WHAT)
        root.only(WHAT, "entries", "pending", "floor")
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
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("The $WHAT is malformed: ${error.message}", error)
        }
    }
}

/** A change request as [ChangeRequestCodec] writes it. */
object ChangeRequestCacheCodec : Codec<ChangeRequest> {
    override fun encode(value: ChangeRequest): ByteArray = ChangeRequestCodec.encode(value)

    override fun decode(bytes: ByteArray): ChangeRequest = ChangeRequestCodec.decode(bytes)
}

/** A staging slot as [ChangeRequestCodec] writes it. */
object StageSlotCodec : Codec<StageSlot> {
    override fun encode(value: StageSlot): ByteArray = ChangeRequestCodec.encode(value)

    override fun decode(bytes: ByteArray): StageSlot = ChangeRequestCodec.decodeSlot(bytes)
}

/** A set of document keys as a sorted JSON array of their encodings, so equal sets have equal bytes and digests. */
object SnapshotKeySetCodec : Codec<Set<SnapshotKey>> {
    private const val WHAT = "back-reference set"

    override fun encode(value: Set<SnapshotKey>): ByteArray {
        val array = factory.arrayNode()
        value.map(SnapshotKeyEncoder::encode).sorted().forEach(array::add)
        return CoSecJsonSerializer.writeValueAsBytes(array)
    }

    override fun decode(bytes: ByteArray): Set<SnapshotKey> =
        read(bytes, WHAT).items(WHAT).mapTo(LinkedHashSet()) { item ->
            val text = item.text(WHAT)
            SnapshotKeyEncoder.decode(text) ?: malformed(WHAT, "[$text] is not a document key")
        }
}

/** Listed entries and when each was listed, as a JSON object sorted by entry, so equal lists have equal digests. */
object ListedCodec : Codec<Map<String, Instant>> {
    private const val WHAT = "control list"

    override fun encode(value: Map<String, Instant>): ByteArray {
        val node = factory.objectNode()
        value.entries.sortedBy { it.key }.forEach { (entry, listedAt) -> node.put(entry, listedAt.toString()) }
        return CoSecJsonSerializer.writeValueAsBytes(node)
    }

    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = read(bytes, WHAT)
        if (!node.isObject) malformed(WHAT, "$node is not an object")
        return node.properties().associateTo(LinkedHashMap()) { (entry, listedAt) -> entry to listedAt.instant(WHAT) }
    }
}
