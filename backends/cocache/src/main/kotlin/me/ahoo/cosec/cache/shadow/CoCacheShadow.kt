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

package me.ahoo.cosec.cache.shadow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.createLocked
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.runtime.shadow.InstanceStatus
import me.ahoo.cosec.runtime.shadow.ShadowCodec
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.runtime.shadow.ShadowReportSink
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The caches of the shadow window (RFC 0007 §6.4, §6.5):
 *
 * | Cache | Redis key | Policy |
 * |---|---|---|
 * | candidates | `<p>:candidate:<docKey>` | store of record: the control plane's only copy while a change stages |
 * | reports | `<p>:shadow:report:<window>:<instance>` | one instance's report of one window; expires [REPORT_TTL] after its last write |
 * | reporters | `<p>:shadow:reporters:<window>` | the instances that reported on one window, only ever added to; expires as the reports |
 * | statuses | `<p>:shadow:status:<instance>` | one instance's last status; expires [STATUS_TTL] after its last write |
 * | instances | `<p>:shadow:instances:all` | the instances that have a status, each with when it listed itself; expires as the reports |
 *
 * All five are `Store`s. Reports and statuses are observations, so they expire; a failed read still throws, so that an
 * outage is never read as "no samples" or "no instance". Each instance writes only its own report and status keys
 * (round-2 review R1, #1214), so that one instance can never replace another's numbers, an ACL can confine each
 * instance to its own keys, and a heartbeat costs the size of one status, not of the cluster's.
 */
internal object ShadowCacheSpecs {
    /** Longer than the longest window (24 hours) and the week a change may stay unpublished. */
    val REPORT_TTL: Duration = Duration.ofDays(8)

    /** Statuses older than a minute are not counted (RFC 0007 §6.5); the key outlives that only to be read. */
    val STATUS_TTL: Duration = Duration.ofMinutes(2)

    /** The key of the one list of instances. */
    const val INDEX_KEY: String = "all"

    /** An instance lists itself again after this long, so that its entry outlives [INDEX_EXPIRY]. */
    val INDEX_REFRESH: Duration = Duration.ofHours(1)

    /**
     * An entry not refreshed for this long belongs to an instance that is gone: the next instance to write the list
     * drops it. Readers skip an entry whose status has expired in any case, so this only bounds the list.
     */
    val INDEX_EXPIRY: Duration = Duration.ofHours(3)

    fun candidate(keyPrefix: String = CoSec.COSEC): StoreSpec<SnapshotKey, StoredCandidate> =
        CoSecCacheSpecs.storeOfRecord(
            CoSecCacheSpecs.CANDIDATE_CACHE_NAME,
            keyPrefix,
            SnapshotKeyEncoder,
            StoredCandidateCodec
        )
            .build()

    fun reports(keyPrefix: String = CoSec.COSEC): StoreSpec<String, ShadowReport> =
        observation(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME, keyPrefix, ShadowReportCodec, REPORT_TTL)

    fun reporters(keyPrefix: String = CoSec.COSEC): StoreSpec<String, Set<String>> =
        observation(CoSecCacheSpecs.SHADOW_REPORTER_CACHE_NAME, keyPrefix, ReportersCodec, REPORT_TTL)

    /** The key of [instance]'s report of [window], under the reports' namespace. */
    fun reportKey(window: String, instance: String): String = "$window:$instance"

    /** One instance's status under its id ([ShadowReport.isInstanceId]). */
    fun statuses(keyPrefix: String = CoSec.COSEC): StoreSpec<String, InstanceStatus> =
        observation(CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, keyPrefix, InstanceStatusCodec, STATUS_TTL)

    /** The instances that have a status to read, by when each last listed itself, under [INDEX_KEY]. */
    fun instances(keyPrefix: String = CoSec.COSEC): StoreSpec<String, Map<String, Instant>> =
        observation(CoSecCacheSpecs.INSTANCE_INDEX_CACHE_NAME, keyPrefix, InstanceIndexCodec, REPORT_TTL)

    /** A `Store` whose entries expire [ttl] after their last write; a failed read throws ([CoSecCacheSpecs.locks]). */
    private fun <V : Any> observation(
        cacheName: String,
        keyPrefix: String,
        codec: Codec<V>,
        ttl: Duration
    ): StoreSpec<String, V> {
        val layout = CoSecCacheSpecs.layout(keyPrefix, cacheName)
        return StoreSpec.builder<String, V>(cacheName)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder { it }
            .codec(codec)
            .policy { this.ttl = ttl }
            .build()
    }
}

internal object StoredCandidateCodec : Codec<StoredCandidate> {
    override fun encode(value: StoredCandidate): ByteArray = ShadowCodec.encode(value)

    override fun decode(bytes: ByteArray): StoredCandidate = ShadowCodec.decodeCandidate(bytes)
}

internal object ShadowReportCodec : Codec<ShadowReport> {
    override fun encode(value: ShadowReport): ByteArray = ShadowCodec.encodeReport(value)

    override fun decode(bytes: ByteArray): ShadowReport = ShadowCodec.decodeReport(bytes)
}

/** The instances that reported on a window, as a sorted JSON array of their ids. */
internal object ReportersCodec : Codec<Set<String>> {
    override fun encode(value: Set<String>): ByteArray = CoSecJson.mapper.writeValueAsBytes(value.sorted())

    override fun decode(bytes: ByteArray): Set<String> {
        val node = CoSecJson.mapper.readTree(bytes)
        require(
            node.isArray && node.values().all { it.isString }
        ) { "The reporters of a window are an array of instance ids" }
        return node.values().mapTo(LinkedHashSet()) { it.asString() }
    }
}

/** One instance's status, in the format of [ShadowCodec.encodeStatuses] with the one instance. */
internal object InstanceStatusCodec : Codec<InstanceStatus> {
    override fun encode(value: InstanceStatus): ByteArray = ShadowCodec.encodeStatuses(mapOf(value.instance to value))

    override fun decode(bytes: ByteArray): InstanceStatus {
        val statuses = ShadowCodec.decodeStatuses(bytes)
        require(statuses.size == 1) { "A stored status is one instance's, not ${statuses.size}" }
        return statuses.values.single()
    }
}

/** The instances that have a status, as a JSON object of instance id to the time it listed itself. */
internal object InstanceIndexCodec : Codec<Map<String, Instant>> {
    override fun encode(value: Map<String, Instant>): ByteArray =
        CoSecJson.mapper.writeValueAsBytes(value.toSortedMap().mapValues { it.value.toString() })

    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = CoSecJson.mapper.readTree(bytes)
        require(
            node.isObject && node.values().all { it.isString }
        ) { "The instances with a status are an object of instance id to time" }
        return node.properties().associate { (instance, at) -> instance to Instant.parse(at.asString()) }
    }
}

/**
 * The data plane's [CandidateStore] on Redis: reads `<p>:candidate:<docKey>` from Redis, never a local copy
 * (`lookupRemote`, CoCache 8.0.0-rc.1), and throws when Redis fails.
 */
@InternalCoSecApi
class CoCacheCandidateStore(private val candidates: Store<SnapshotKey, StoredCandidate>) : CandidateStore {
    override suspend fun candidate(key: SnapshotKey): StoredCandidate? =
        when (val read = candidates.suspending().lookupRemote(key)) {
            is Lookup.Present -> read.value
            is Lookup.Absent -> null
        }

    @InternalCoSecApi
    companion object {
        fun create(factory: CoCacheFactory, keyPrefix: String = CoSec.COSEC): CoCacheCandidateStore =
            CoCacheCandidateStore(factory.createLocked(ShadowCacheSpecs.candidate(keyPrefix)))
    }
}

/**
 * Shadow reports and instance statuses on Redis (RFC 0007 §6.4, §6.5). Each instance's report of a window has a key
 * of its own, `<window>:<instance>`, which only that instance writes, replacing its previous cumulative report; an
 * instance id has no `:` ([ShadowReport.isInstanceId]) and a window id none either, so a key names one report only.
 * The report is written first, then the instance is added to the window's reporters with `putIfMatch` over the value
 * read; every write checks that it is still there. A window's total is the sum of one report per instance (review R1),
 * and a listed instance without a report is an error, never left out.
 *
 * The reporters are a value every instance writes: an instance can remove another from them. The control plane keeps
 * every instance it has seen with the change (`ShadowState.reporters`), so a removal after that is an error too; one
 * before the control plane looks again is not detected.
 *
 * Each instance also writes its status under a key of its own, `<instance>`, so that a heartbeat writes one status, not
 * every instance's (#1214). Which instances have one is a list, `instances`, that changes when an instance joins or
 * leaves: an instance lists itself with a conditional read of it that costs nothing while the list is unchanged, and
 * again every [ShadowCacheSpecs.INDEX_REFRESH]; whoever writes the list drops entries nobody refreshed for
 * [ShadowCacheSpecs.INDEX_EXPIRY]. [statuses] reads the list and each listed status, and skips a listed instance whose
 * status has expired (it is gone), as one older than a minute. A status or list that cannot be read fails it.
 */
class CoCacheShadowReports(
    private val reports: Store<String, ShadowReport>,
    private val reporters: Store<String, Set<String>>,
    private val statuses: Store<String, InstanceStatus>,
    private val instances: Store<String, Map<String, Instant>>,
    private val clock: Clock = Clock.systemUTC(),
) : ShadowReportSink, ShadowReportReader {
    /** For each instance this object writes for: the list as it was read when its entry was current, and until when. */
    private val listed = ConcurrentHashMap<String, Listed>()

    private class Listed(val digest: Digest, val until: Instant)

    override suspend fun write(report: ShadowReport) {
        require(ShadowReport.isInstanceId(report.instance)) { "[${report.instance}] is not an instance id" }
        require(report.window.isNotEmpty() && ':' !in report.window) { "[${report.window}] is not a window id" }
        require(report.counts.values.all { it >= 0 }) { "A report's counts are never negative" }
        reports.async().put(ShadowCacheSpecs.reportKey(report.window, report.instance), report).await()
        if (read(reporters, report.window)?.first?.contains(report.instance) != true) {
            update(reporters, report.window, { it + report.instance }, emptySet())
        }
    }

    override suspend fun write(status: InstanceStatus) {
        require(ShadowReport.isInstanceId(status.instance)) { "[${status.instance}] is not an instance id" }
        statuses.async().put(status.instance, status).await()
        list(status.instance)
    }

    /**
     * Makes sure [instance] is on the list with an entry that is current. A list as read before (same digest) that still
     * holds a current entry is the common case, and answers without a value; one that lost the entry (it expired, or
     * the list was lost) is written again at the next heartbeat.
     */
    private suspend fun list(instance: String) {
        val now = clock.instant()
        val known = listed[instance]
        when (val read = instances.async().getIfChanged(ShadowCacheSpecs.INDEX_KEY, known?.digest).await()) {
            ConditionalGet.Unchanged -> if (known != null && now.isBefore(known.until)) return
            is ConditionalGet.Present -> {
                val at = read.value[instance]
                if (at != null && now.isBefore(at.plus(ShadowCacheSpecs.INDEX_REFRESH))) {
                    listed[instance] = Listed(read.digest, at.plus(ShadowCacheSpecs.INDEX_REFRESH))
                    return
                }
            }
            ConditionalGet.Absent -> Unit
        }
        val written = update(
            instances,
            ShadowCacheSpecs.INDEX_KEY,
            { current ->
                current.filterValues { now.isBefore(it.plus(ShadowCacheSpecs.INDEX_EXPIRY)) } + (instance to now)
            },
            emptyMap(),
        )
        // The digest the list has now, if nobody wrote it since: otherwise the next heartbeat reads it once.
        listed[instance] = Listed(
            Digest.ofPayload(InstanceIndexCodec.encode(written)),
            now.plus(ShadowCacheSpecs.INDEX_REFRESH)
        )
    }

    /**
     * One report per instance listed as having reported on [window]. A listed instance without a report (expired,
     * removed), an id that is not an instance id, and a report stored under another instance's or window's key are
     * errors, not samples: the window cannot close on them (fail closed).
     */
    override suspend fun reports(window: String): List<ShadowReport> {
        val instances = read(reporters, window)?.first.orEmpty()
        check(
            instances.all(ShadowReport::isInstanceId)
        ) { "The reporters of window $window name an invalid instance id" }
        return coroutineScope {
            instances.sorted().map { instance ->
                async {
                    val key = ShadowCacheSpecs.reportKey(window, instance)
                    val report = checkNotNull(
                        read(reports, key)?.first
                    ) { "[$key] is listed as reported but has no report" }
                    report.also { checkOwner(it, window, instance) }
                }
            }.awaitAll()
        }
    }

    private fun checkOwner(report: ShadowReport, window: String, instance: String) {
        check(report.window == window && report.instance == instance) {
            "The report under [${ShadowCacheSpecs.reportKey(
                window,
                instance
            )}] is ${report.instance}'s of ${report.window}"
        }
    }

    override suspend fun statuses(): List<InstanceStatus> {
        val since = clock.instant().minus(ShadowReportReader.STATUS_TTL)
        val members = read(instances, ShadowCacheSpecs.INDEX_KEY)?.first?.keys.orEmpty()
        check(members.all(ShadowReport::isInstanceId)) { "The list of instances names an invalid instance id" }
        val found = members.sorted().chunked(READ_BATCH).flatMap { batch ->
            coroutineScope {
                batch.map { instance ->
                    async {
                        read(statuses, instance)?.first?.also {
                            check(it.instance == instance) { "The status under [$instance] is ${it.instance}'s" }
                        }
                    }
                }.awaitAll()
            }
        }
        return found.filterNotNull().filter { !it.at.isBefore(since) }
    }

    /** Read, change, `putIfMatch`: retried while another instance wrote in between; the value written. */
    private suspend fun <V : Any> update(cache: Store<String, V>, key: String, next: (V) -> V, empty: V): V {
        repeat(MAX_ATTEMPTS) {
            val current = read(cache, key)
            val value = next(current?.first ?: empty)
            if (cache.async().putIfMatch(key, current?.second, value).await() is ConditionalPut.Written) {
                return value
            }
        }
        error("[$key] kept changing while it was written")
    }

    private suspend fun <V : Any> read(cache: Store<String, V>, key: String): Pair<V, Digest>? =
        when (val read = cache.async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value to read.digest
            ConditionalGet.Unchanged -> error("An unconditional read of [$key] was answered as unchanged.")
            ConditionalGet.Absent -> null
        }

    companion object {
        private const val MAX_ATTEMPTS = 16

        /** How many statuses [statuses] reads at once. */
        private const val READ_BATCH = 64

        /** Creates the report caches under [keyPrefix] through [factory], with CoSec's locks ([CoSecCacheSpecs.locks]). */
        fun create(
            factory: CoCacheFactory,
            keyPrefix: String = CoSec.COSEC,
            clock: Clock = Clock.systemUTC()
        ): CoCacheShadowReports =
            CoCacheShadowReports(
                factory.createLocked(ShadowCacheSpecs.reports(keyPrefix)),
                factory.createLocked(ShadowCacheSpecs.reporters(keyPrefix)),
                factory.createLocked(ShadowCacheSpecs.statuses(keyPrefix)),
                factory.createLocked(ShadowCacheSpecs.instances(keyPrefix)),
                clock
            )
    }
}
