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

package me.ahoo.cosec.migrate

import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisKeyCommands
import io.lettuce.core.api.sync.RedisStringCommands
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * The raw Redis commands [CoCache8Upgrade] needs, on a single node or a cluster: it reads what CoCache 6 wrote byte for
 * byte, which no CoCache 8 cache can.
 */
interface RawRedis {
    /** Every key matching the glob [pattern]. */
    fun scan(pattern: String): List<String>

    fun type(key: String): String

    fun get(key: String): ByteArray?

    /** Milliseconds [key] has left to live: `-1` without an expiry, `-2` when it is gone. */
    fun pttl(key: String): Long

    fun del(keys: Collection<String>): Long

    fun set(key: String, value: ByteArray)
}

/** [RawRedis] over Lettuce's commands, of a node or of a cluster, whose `SCAN` covers every node. */
internal class LettuceRawRedis(
    private val keys: RedisKeyCommands<ByteArray, ByteArray>,
    private val strings: RedisStringCommands<ByteArray, ByteArray>,
) : RawRedis {
    override fun scan(pattern: String): List<String> {
        val found = ArrayList<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = keys.scan(cursor, ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT))
            result.keys.mapTo(found) { it.decodeToString() }
            cursor = result
        } while (!result.isFinished)
        return found.distinct()
    }

    override fun type(key: String): String = keys.type(key.encodeToByteArray())

    override fun get(key: String): ByteArray? = strings.get(key.encodeToByteArray())

    override fun pttl(key: String): Long = keys.pttl(key.encodeToByteArray())

    override fun del(keys: Collection<String>): Long =
        this.keys.del(*keys.map { it.encodeToByteArray() }.toTypedArray())

    override fun set(key: String, value: ByteArray) {
        strings.set(key.encodeToByteArray(), value)
    }

    private companion object {
        const val SCAN_COUNT = 1000L
    }
}

/**
 * `cosec-migrate cocache8`: moves a CoSec 8.0.0-alpha deployment's data from CoCache 6 to CoCache 8. CoCache 8 does not
 * read CoCache 6's format 1 ([CoCache6Format]) and fails closed on it: a request that reads such a document is `503`,
 * a token revoked in it `503` instead of `401`, the control plane `500`. Run it with every CoSec instance stopped:
 *
 * - the snapshot documents are published again as the first revision of each ([DocumentPublisher]), without their
 *   provenance, whose parent digest is a SHA-1 CoCache 8 does not know;
 * - the revocations are written again, each with what remains of its lifetime, so a revoked token stays revoked;
 * - the rest is deleted: the control plane's history (it starts again with the published documents), its change
 *   requests, staging slots and leases, the shadow windows' candidates and reports, and the cached memberships, which
 *   fill again on their own.
 *
 * [plan] only reads. [apply] writes nothing unless every document decodes and passes `publish`'s checks, and nothing
 * while a change is pending (a staging slot or a candidate) unless told to discard it. Values not in CoCache 6's format,
 * such as what an earlier run wrote, are left as they are, so a second run finishes the first.
 */
class CoCache8Upgrade(private val redis: RawRedis, private val keyPrefix: String) {
    /** A revocation to write again: [value] as stored (the table's JSON), [ttl] what remains of its lifetime. */
    class Revocation(val cacheName: String, val key: String, val value: ByteArray, val ttl: Duration?)

    class Plan(
        val documents: Map<SnapshotKey, PolicyDocument>,
        val revocations: List<Revocation>,
        /** The CoCache 6 values deleted, by family (cache name). */
        val deletions: Map<String, List<String>>,
        /** Staging slots and candidates: a change in flight, lost by the upgrade. */
        val pending: List<String>,
        /** Keys under CoSec's families that hold no CoCache 6 value, left as they are. */
        val skipped: List<String>,
        /** Why nothing may be written: a document or a revocation that does not decode. */
        val refusals: List<String>,
        /** SHA-256 of every CoCache 6 value read, by key, for the export's report. */
        val inputSha256: String,
        /** An earlier run that deleted the documents and did not publish all of them, from its marker. */
        val unfinished: Unfinished? = null,
        /** Whether the platform document is a CoCache 8 head already. */
        val platformHead: Boolean = false,
    )

    /** What an earlier run's marker says: where it exported the documents, and which. */
    class Unfinished(val out: String, val documents: List<String>)

    /** What [plan] has found so far. */
    private inner class Reading {
        val documents = sortedMapOf<String, Pair<SnapshotKey, PolicyDocument>>()
        val revocations = ArrayList<Revocation>()
        val deletions = sortedMapOf<String, MutableList<String>>()
        val pending = ArrayList<String>()
        val skipped = ArrayList<String>()
        val refusals = ArrayList<String>()
        val input: MessageDigest = MessageDigest.getInstance("SHA-256")

        /** Files the value under [storageKey] of [cacheName], whose storage path is [path]. */
        fun read(cacheName: String, path: String, storageKey: String) {
            val raw = if (redis.type(storageKey) == STRING) redis.get(storageKey) else null
            if (raw == null || !isCoCache6(raw)) {
                skipped += storageKey
                return
            }
            input.update(storageKey.encodeToByteArray())
            input.update(raw)
            if (raw[STATE_INDEX] !in STATES) {
                // As CoCache6Format.scalar refuses it: deleting what it cannot read could drop a revocation.
                refusals += "[$storageKey] holds a CoCache 6 value in an unknown state ${raw[STATE_INDEX]}."
                return
            }
            val encodedKey = storageKey.removePrefix("$path:")
            if (raw[STATE_INDEX] == CoCache6Format.STATE_VALUE) {
                value(cacheName, storageKey, encodedKey, raw.copyOfRange(CoCache6Format.HEADER_SIZE, raw.size))
            } else {
                // A cached absence or a load lease: no value, nothing to keep.
                deletions.getOrPut(cacheName) { ArrayList() } += storageKey
            }
        }

        /** Files the CoCache 6 value [payload] stored under [storageKey]. */
        private fun value(cacheName: String, storageKey: String, encodedKey: String, payload: ByteArray) {
            if (cacheName in REVOCATIONS) {
                // Written again over the old value, never deleted.
                revocation(cacheName, storageKey, encodedKey, payload, refusals)?.let { revocations += it }
                return
            }
            deletions.getOrPut(cacheName) { ArrayList() } += storageKey
            when {
                cacheName == CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME ->
                    document(storageKey, encodedKey, payload, refusals)?.let { documents[encodedKey] = it }

                cacheName in PENDING -> pending += storageKey

                cacheName == CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME && encodedKey == OPEN_CHANGES ->
                    openChanges(storageKey, payload)
            }
        }

        /** A change still open, a draft included, is one the upgrade would drop. */
        @Suppress("TooGenericExceptionCaught")
        private fun openChanges(storageKey: String, payload: ByteArray) {
            val listed = try {
                reader.readTree(payload) as? ObjectNode ?: error("not a JSON object")
            } catch (error: RuntimeException) {
                refusals += "[$storageKey] lists open changes that do not decode: ${error.message}"
                return
            }
            if (!listed.isEmpty) {
                pending += "$storageKey (${listed.propertyNames().joinToString()})"
            }
        }

        fun plan(unfinished: Unfinished?, platformHead: Boolean): Plan = Plan(
            documents.values.associate { it },
            revocations,
            deletions,
            pending,
            skipped,
            refusals,
            input.digest().joinToString("") { "%02x".format(it) },
            unfinished,
            platformHead,
        )
    }

    /** Reads every family CoSec keeps through CoCache under [keyPrefix]; writes nothing. */
    fun plan(): Plan {
        val reading = Reading()
        for (cacheName in CoSecCacheSpecs.CACHE_NAMES.sorted()) {
            val path = CoSecCacheSpecs.storagePath(keyPrefix, cacheName)
            redis.scan(glob(path) + ":*").sorted().forEach { reading.read(cacheName, path, it) }
        }
        val platform = "${CoSecCacheSpecs.storagePath(keyPrefix, CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)}:" +
            SnapshotKeyEncoder.PLATFORM
        val platformHead = redis.type(platform) == STRING && redis.get(platform)?.let { !isCoCache6(it) } == true
        return reading.plan(unfinished(), platformHead)
    }

    /** The marker an earlier run left, if it stopped between deleting the documents and publishing every one. */
    fun unfinished(): Unfinished? {
        val marker = redis.get(markerKey(keyPrefix)) ?: return null
        val tree = reader.readTree(marker)
        return Unfinished(
            tree.get(OUT).asString(),
            tree.get(DOCUMENTS).iterator().asSequence().map { it.asString() }.toList()
        )
    }

    /**
     * Deletes the marker of an interrupted run once every document it lists has a head again, as `publish --resume`
     * leaves them: [hasHead] answers for an encoded document key. Whether it was deleted.
     */
    fun finish(hasHead: (SnapshotKey) -> Boolean): Boolean {
        val unfinished = unfinished() ?: return false
        val missing = unfinished.documents.filterNot { encoded -> SnapshotKeyEncoder.decode(encoded)?.let(hasHead) == true }
        if (missing.isNotEmpty()) {
            return false
        }
        redis.del(listOf(markerKey(keyPrefix)))
        return true
    }

    /**
     * Why [plan] may not be written, empty when it may: a refusal of [plan] itself, a document `publish` would refuse
     * (it does not compile, misses a reference, holds a break-glass binding, or there is no platform document), or a
     * change in flight unless [discardPending].
     */
    // Whatever publish would throw refuses the run, with its message.
    @Suppress("TooGenericExceptionCaught")
    fun check(plan: Plan, discardPending: Boolean): List<String> {
        val problems = ArrayList(plan.refusals)
        plan.unfinished?.let {
            problems += "An earlier cocache8 run did not finish: it deleted the CoCache 6 documents and stopped before " +
                "it had published all of ${it.documents}, exported to ${it.out}. Do not run cocache8 to finish it: it " +
                "cannot see the documents it deleted. Publish them from the export with publish --resume (below)."
        }
        if (plan.unfinished == null && plan.documents.isEmpty() && !plan.platformHead) {
            problems += "There is neither a CoCache 6 document to move nor a platform document: a cocache8 run that " +
                "stopped after deleting the documents leaves this. Publish them from its export with publish --resume, " +
                "or publish the documents first."
        }
        if (plan.pending.isNotEmpty() && !discardPending) {
            problems += "A change is in flight, in ${plan.pending}: publish or abandon it first, or discard it " +
                "(--discard-pending); the upgrade keeps no staging slot, candidate or open change."
        }
        if (plan.documents.isNotEmpty()) {
            try {
                DocumentPublisher.check(plan.documents)
            } catch (error: RuntimeException) {
                problems += "The documents cannot be published: ${error.message}"
            }
        }
        return problems
    }

    /**
     * Writes [plan], which [check] accepted. The documents go first to [out], in `publish`'s layout, and a marker
     * (`<p>:meta:cocache8`) records it before anything in Redis changes; then the revocations through [factory]'s
     * CoCache 8 tables, the deletion of the other CoCache 6 values and the documents through [store]. The marker goes only
     * once every document is published: a run that stops in between leaves it, and a later run refuses to start.
     */
    fun apply(plan: Plan, factory: CoCacheFactory, store: ControlStore, out: Path): PublishResult? {
        if (plan.documents.isNotEmpty()) {
            ConvertedDirectory.write(export(plan), out)
            val marker = CoSecJson.mapper.createObjectNode()
            marker.put(OUT, out.toAbsolutePath().toString())
            marker.putArray(DOCUMENTS).apply { plan.documents.keys.forEach { add(SnapshotKeyEncoder.encode(it)) } }
            redis.set(markerKey(keyPrefix), CoSecJson.mapper.writeValueAsBytes(marker))
        }
        val tokens = factory.createLocked(CoSecCacheSpecs.revokedToken(keyPrefix))
        val subjects = factory.createLocked(CoSecCacheSpecs.revokedSubject(keyPrefix))
        for (revocation in plan.revocations) {
            // A plain put overwrites what Redis holds, CoCache 6's value included: the revocation is never missing.
            if (revocation.cacheName == CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME) {
                write(tokens, CoSecCacheSpecs.revokedToken().codec, revocation)
            } else {
                write(subjects, CoSecCacheSpecs.revokedSubject().codec, revocation)
            }
        }
        val deletions = plan.deletions.values.flatten()
        if (deletions.isNotEmpty()) {
            redis.del(deletions)
        }
        if (plan.documents.isEmpty()) {
            return null
        }
        val published = DocumentPublisher(store).publish(plan.documents)
        redis.del(listOf(markerKey(keyPrefix)))
        return published
    }

    private fun export(plan: Plan): Conversion = Conversion(
        plan.documents,
        Provenance(emptyList(), emptyList()),
        ConversionReport(
            emptyList(),
            emptyList(),
            emptyList(),
            mapOf("documents" to plan.documents.size, "revocations" to plan.revocations.size),
            plan.inputSha256,
        ),
    )

    private fun <V : Any> write(table: Store<String, V>, codec: Codec<V>, revocation: Revocation) {
        val value = codec.decode(revocation.value)
        val ttl = revocation.ttl
        if (ttl == null) table.put(revocation.key, value) else table.put(revocation.key, value, ttl)
    }

    // Whatever the table's codec throws refuses the run, with its message.
    @Suppress("TooGenericExceptionCaught")
    private fun revocation(
        cacheName: String,
        storageKey: String,
        encodedKey: String,
        payload: ByteArray,
        refusals: MutableList<String>,
    ): Revocation? {
        val codec = if (cacheName == CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME) {
            CoSecCacheSpecs.revokedToken().codec
        } else {
            CoSecCacheSpecs.revokedSubject().codec
        }
        try {
            codec.decode(payload)
        } catch (error: RuntimeException) {
            refusals += "[$storageKey] holds a revocation that does not decode: ${error.message}"
            return null
        }
        val pttl = redis.pttl(storageKey)
        return when {
            pttl == NO_EXPIRY -> Revocation(cacheName, encodedKey, payload, null)
            pttl > 0 -> Revocation(cacheName, encodedKey, payload, Duration.ofMillis(pttl))
            // Gone while being read: it expired, as it would have.
            else -> null
        }
    }

    /** The document stored under [encodedKey], without its provenance; null, with a refusal, when it does not decode. */
    @Suppress("TooGenericExceptionCaught")
    private fun document(
        storageKey: String,
        encodedKey: String,
        payload: ByteArray,
        refusals: MutableList<String>,
    ): Pair<SnapshotKey, PolicyDocument>? {
        val key = SnapshotKeyEncoder.decode(encodedKey)
        if (key == null) {
            refusals += "[$storageKey] names no document."
            return null
        }
        val (kind, owner) = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
        }
        return try {
            val tree = reader.readTree(payload) as? ObjectNode ?: error("not a JSON object")
            // A parent digest of CoCache 6 is a SHA-1 no CoCache 8 record has: the chain starts again with this revision.
            tree.remove(PROVENANCE)
            key to PolicyDocumentCodec.decode(kind, owner, tree)
        } catch (error: RuntimeException) {
            refusals += "[$storageKey] holds a document that does not decode: ${error.message}"
            null
        }
    }

    companion object {
        const val STRING = "string"
        private const val STATE_INDEX = 2
        private const val NO_EXPIRY = -1L
        private const val PROVENANCE = "provenance"
        private val PENDING = setOf(CoSecCacheSpecs.STAGE_CACHE_NAME, CoSecCacheSpecs.CANDIDATE_CACHE_NAME)
        private val STATES = setOf(CoCache6Format.STATE_VALUE, CoCache6Format.STATE_MISSING, CoCache6Format.STATE_LEASE)
        private const val OPEN_CHANGES = "open-changes"
        private const val OUT = "out"
        private const val DOCUMENTS = "documents"

        /** Where a run records that it deleted the documents and has not published them all yet. */
        fun markerKey(keyPrefix: String): String = "${keyPrefix.trimEnd(':')}:meta:cocache8"

        /** The `publish` that finishes a run stopped after its deletions, complete. */
        fun resumeCommand(uri: String, keyPrefix: String, cluster: Boolean, out: String): String =
            "cosec-migrate publish --to-redis $uri --key-prefix $keyPrefix" +
                (if (cluster) " --cluster" else "") + " --in $out --resume"

        private val REVOCATIONS = setOf(
            CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME,
            CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME
        )
        private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

        /** Whether [raw] is a scalar as CoCache 6 wrote it: its magic byte and format 1. */
        fun isCoCache6(raw: ByteArray): Boolean =
            raw.size >= CoCache6Format.HEADER_SIZE && raw[0] == CoCache6Format.MAGIC && raw[1] == CoCache6Format.FORMAT

        /** [text] with the glob characters of `SCAN MATCH` escaped. */
        fun glob(text: String): String = text.replace(Regex("""([\\*?\[\]])"""), """\\$1""")
    }
}
