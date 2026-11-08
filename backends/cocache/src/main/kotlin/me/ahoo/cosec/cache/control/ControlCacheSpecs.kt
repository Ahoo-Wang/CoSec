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

package me.ahoo.cosec.cache.control

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/** A revision record: a document's digest under its document's key (RFC 0007 §5.2). */
internal data class RevisionRecordKey(val document: SnapshotKey, val digest: SnapshotDigest)

/** The referrers of one policy of one document (RFC 0007 §5.8). */
internal data class ReferenceKey(val target: SnapshotKey, val policy: PolicyId)

/**
 * The caches of `CoCacheControlStore` (RFC 0007 §5.2), next to the snapshot heads of [CoSecCacheSpecs.policySnapshot]:
 *
 * | Cache | Redis key |
 * |---|---|
 * | revision records | `<p>:control:revision:<docKey>:SHA-256:<hex>` |
 * | revision index | `<p>:control:revisions:<docKey>` |
 * | back-references | `<p>:control:refs:<docKey>:<policyId>` |
 * | leases | `<p>:control:lease:<name>` |
 * | lists | `<p>:control:list:documents`, `<p>:control:list:open-changes` |
 * | app list | `<p>:control:apps:all` |
 * | change requests | `<p>:control:change:<changeId>` |
 * | staging slots | `<p>:control:stage:<docKey>` |
 *
 * Digests in keys and values are [SnapshotDigest]'s text form, `SHA-256:<hex>`.
 *
 * All of them are [storeOfRecord] `Store`s, locked by [CoSecCacheSpecs.locks] (`ttl` forever, failed reads throw):
 * records and indexes are not data-plane policy data, but reading a failure as "absent" would let the control plane
 * publish on a wrong base (RFC 0007 §5.2). Leases expire through the lifetime of each write instead. Keys and values are
 * encoded explicitly, without reflection.
 */
internal object ControlCacheSpecs {
    fun revisionRecord(keyPrefix: String = CoSec.COSEC): StoreSpec<RevisionRecordKey, SnapshotDocument> =
        storeOfRecord(
            CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME,
            keyPrefix,
            RevisionRecordKeyEncoder,
            SnapshotDocumentCodec,
        ).build()

    fun revisionIndex(keyPrefix: String = CoSec.COSEC): StoreSpec<SnapshotKey, RevisionIndex> =
        storeOfRecord(
            CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME,
            keyPrefix,
            SnapshotKeyEncoder,
            RevisionIndexCodec
        ).build()

    fun referenceIndex(keyPrefix: String = CoSec.COSEC): StoreSpec<ReferenceKey, Map<SnapshotKey, Long>> =
        storeOfRecord(
            CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
            keyPrefix,
            ReferenceKeyEncoder,
            ReferrersCodec,
        ).build()

    fun changeRequest(keyPrefix: String = CoSec.COSEC): StoreSpec<ChangeId, ChangeRequest> =
        storeOfRecord(
            CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<ChangeId> { it.value },
            ChangeRequestCacheCodec,
        ).build()

    fun stage(keyPrefix: String = CoSec.COSEC): StoreSpec<SnapshotKey, StageSlot> =
        storeOfRecord(CoSecCacheSpecs.STAGE_CACHE_NAME, keyPrefix, SnapshotKeyEncoder, StageSlotCodec).build()

    /** What [CoCacheControlStore.documents] and [CoCacheControlStore.openChanges] read: each entry with when it was listed. */
    fun list(keyPrefix: String = CoSec.COSEC): StoreSpec<String, Map<String, Instant>> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            ListedCodec
        ).build()

    /** What [CoCacheControlStore.apps] reads: one value, under the key [CoCacheControlStore]'s `APP_LIST`. */
    fun appList(keyPrefix: String = CoSec.COSEC): StoreSpec<String, AppList> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            AppListCodec
        ).build()

    fun lease(keyPrefix: String = CoSec.COSEC): StoreSpec<String, String> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            StringCodec
        ).build()
}

/**
 * `<docKey>:<digest>`, the digest as `SHA-256:<hex>`. A document key ends before the first `:` after its kind (ids hold
 * no `:`), so the encoding is injective.
 */
internal object RevisionRecordKeyEncoder : KeyEncoder<RevisionRecordKey> {
    override fun encode(key: RevisionRecordKey): String = "${SnapshotKeyEncoder.encode(key.document)}:${key.digest}"
}

/** `<docKey>:<policyId>`: the document key ends before the first `:` after its kind, so policy ids may contain `:`. */
internal object ReferenceKeyEncoder : KeyEncoder<ReferenceKey> {
    /** A policy id with an unpaired surrogate names no key: the Redis key would write it as `?` (RC1-52, `IDN-10`). */
    override fun encode(key: ReferenceKey): String {
        require(Identifiers.isWellFormed(key.policy.value)) {
            "Policy [${Identifiers.printable(key.policy.value)}] names no reference key: an unpaired UTF-16 surrogate " +
                "would share it with another policy."
        }
        return "${SnapshotKeyEncoder.encode(key.target)}:${key.policy.value}"
    }
}

private val factory = JsonNodeFactory.instance
private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

private fun read(bytes: ByteArray, what: String): JsonNode = try {
    reader.readTree(bytes) ?: throw SnapshotFormatException("The $what is empty.")
} catch (error: JacksonException) {
    throw SnapshotFormatException(
        "The $what is not JSON: ${SnapshotKeyText.printable(error.originalMessage.orEmpty())}",
        error
    )
}

/** Stored content quoted in [reason] has its control characters escaped: the message reaches log lines (RC1-15). */
private fun malformed(what: String, reason: String): Nothing = throw SnapshotFormatException(
    "The $what is malformed: ${SnapshotKeyText.printable(reason)}."
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
    if (!SnapshotDigest.isDigest(text)) malformed(what, "[$text] is not a digest")
    return SnapshotDigest.parse(text)
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

/**
 * A [RevisionIndex] as JSON: `{"entries": [...], "pending": [...], "floor": 1}`, with `"reclaiming": [...]` only when
 * records are left to delete (#1226), so that an index with none is written as earlier builds wrote it and they still
 * read it.
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

/** A change request as [ChangeRequestCodec] writes it. */
internal object ChangeRequestCacheCodec : Codec<ChangeRequest> {
    override fun encode(value: ChangeRequest): ByteArray = ChangeRequestCodec.encode(value)

    override fun decode(bytes: ByteArray): ChangeRequest = ChangeRequestCodec.decode(bytes)
}

/** A staging slot as [ChangeRequestCodec] writes it. */
internal object StageSlotCodec : Codec<StageSlot> {
    override fun encode(value: StageSlot): ByteArray = ChangeRequestCodec.encode(value)

    override fun decode(bytes: ByteArray): StageSlot = ChangeRequestCodec.decodeSlot(bytes)
}

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

    /**
     * The JSON array earlier builds wrote, of keys without revisions, reads as each indexed at revision 0: before any
     * revision this build publishes, so a later drop by the referrer removes it and a later reference raises it.
     *
     * A member that is an app key earlier builds wrote and the app id rule now refuses is skipped, with a WARN (R3-1 of
     * #1194's review): such a document can no longer be read or published, and so can never drop its own reference,
     * while one such member would make every write and check of the set fail. Dropping it is safe: an extra referrer
     * only ever blocks a deletion. Anything else that is not a document key, or a revision that is not a non-negative
     * integer, is still malformed.
     */
    override fun decode(bytes: ByteArray): Map<SnapshotKey, Long> {
        val root = read(bytes, WHAT)
        val members: List<Pair<String, Long>> = if (root.isArray) {
            root.items(WHAT).map { it.text(WHAT) to LEGACY_REVISION }
        } else {
            if (!root.isObject) malformed(WHAT, "$root is neither an object nor an array")
            root.properties().map { (key, revision) ->
                val value = revision.long(WHAT)
                if (value < 0) malformed(WHAT, "revision $value of [$key] is negative")
                key to value
            }
        }
        return members.mapNotNull { (text, revision) ->
            val key = SnapshotKeyEncoder.decode(text) ?: if (SnapshotKeyText.isUnnamedApp(text)) {
                log.warn {
                    "A back-reference set names [${SnapshotKeyText.printable(text)}], which no document key can name " +
                        "(SCP-7): that member is skipped."
                }
                return@mapNotNull null
            } else {
                malformed(WHAT, "[$text] is not a document key")
            }
            key to revision
        }.toMap(LinkedHashMap())
    }

    /** The revision a member of the earlier format, without one, is read as. */
    const val LEGACY_REVISION: Long = 0

    private val log = KotlinLogging.logger {}
}

/**
 * The app list as `{"apps": [...sorted], "complete": true}`, so equal lists have equal digests. Entries are kept as
 * written, whatever they are: the grant analysis refuses to trust a list with one that is not an app id, never drops it.
 */
internal object AppListCodec : Codec<AppList> {
    private const val WHAT = "app list"

    override fun encode(value: AppList): ByteArray {
        val root = factory.objectNode()
        root.putArray("apps").apply { value.entries.sorted().forEach(::add) }
        root.put("complete", value.complete)
        return CoSecJson.mapper.writeValueAsBytes(root)
    }

    override fun decode(bytes: ByteArray): AppList {
        val root = read(bytes, WHAT)
        root.only(WHAT, "apps", "complete")
        val complete = root.field("complete", WHAT)
        if (!complete.isBoolean) malformed(WHAT, "$complete is not a boolean")
        return AppList(
            root.field("apps", WHAT).items(WHAT).mapTo(LinkedHashSet()) { it.text(WHAT) },
            complete.booleanValue()
        )
    }
}

/** Listed entries and when each was listed, as a JSON object sorted by entry, so equal lists have equal digests. */
internal object ListedCodec : Codec<Map<String, Instant>> {
    private const val WHAT = "control list"

    override fun encode(value: Map<String, Instant>): ByteArray {
        val node = factory.objectNode()
        value.entries.sortedBy { it.key }.forEach { (entry, listedAt) -> node.put(entry, listedAt.toString()) }
        return CoSecJson.mapper.writeValueAsBytes(node)
    }

    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = read(bytes, WHAT)
        if (!node.isObject) malformed(WHAT, "$node is not an object")
        return node.properties().associateTo(LinkedHashMap()) { (entry, listedAt) -> entry to listedAt.instant(WHAT) }
    }
}
