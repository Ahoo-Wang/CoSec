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

package me.ahoo.cosec.cocache.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.cocache.readRecord
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ResidentReadings
import me.ahoo.cosec.spi.shadow.ShadowReadings
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.shadow.StatusReadings
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
 * status has expired (it is gone), as one older than a minute. A status or list that cannot be read fails it;
 * [statusReadings] names a status that does not decode instead (`CTL-73`).
 */
@InternalCoSecApi
class CoCacheShadowReports @InternalCoSecApi constructor(
    private val reports: Store<String, ShadowReport>,
    private val reporters: Store<String, Set<String>>,
    private val statuses: Store<String, InstanceStatus>,
    private val instances: Store<String, Map<String, Instant>>,
    private val residents: Store<String, ResidentDocuments>,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Reads an instance's status key as the backend stores it, only for the life the key has left: what ages a status
     * that does not decode ([statusReadings]). Null when the store was built without it: such a status then counts while
     * its instance's entry in the list is current, as one without a life does.
     */
    private val statusKey: (suspend (instance: String) -> L1Read)? = null,
) : ShadowReportSink, ShadowReportReader {
    /** For each instance this object writes for: the list as it was read when its entry was current, and until when. */
    private val listed = ConcurrentHashMap<String, Listed>()

    private class Listed(val digest: Digest, val until: Instant)

    /** When each instance's undecodable status was last logged ([warnUnreadable]). */
    private val warned = ConcurrentHashMap<String, Instant>()

    override suspend fun write(report: ShadowReport) {
        require(ShadowReport.isInstanceId(report.instance)) { "[${report.instance}] is not an instance id" }
        require(report.window.isNotEmpty() && ':' !in report.window) { "[${report.window}] is not a window id" }
        require(report.counts.values.all { it >= 0 }) { "A report's counts are never negative" }
        reports.async().put(ShadowCacheSpecs.reportKey(report.window, report.instance), report).await()
        if (read(reporters, report.window)?.first?.contains(report.instance) != true) {
            update(reporters, report.window, { it + report.instance }, emptySet())
        }
    }

    override suspend fun write(resident: ResidentDocuments) {
        require(ShadowReport.isInstanceId(resident.instance)) { "[${resident.instance}] is not an instance id" }
        residents.async().put(resident.instance, resident).await()
    }

    /**
     * The resident records of [instances], read in batches of [READ_BATCH] (`CTL-75`). One that is missing (it expired,
     * or was never written) or does not decode is named unreadable, and `propagation` counts its instance as unknown;
     * one under another instance's key is the store's data being wrong, and fails the call, as a store that does not
     * answer does.
     */
    override suspend fun residents(instances: Set<String>): ResidentReadings {
        val found = instances.sorted().chunked(READ_BATCH).flatMap { batch ->
            coroutineScope { batch.map { instance -> async { instance to resident(instance) } }.awaitAll() }
        }
        return ResidentReadings(
            found.mapNotNull { (instance, read) -> read?.let { instance to it } }.toMap(),
            found.filter { it.second == null }.mapTo(HashSet()) { it.first },
        )
    }

    /** [instance]'s resident record, or null when it has none (quietly) or it does not decode (logged at WARN). */
    private suspend fun resident(instance: String): ResidentDocuments? {
        val resident = try {
            read(residents, instance, "The resident documents of instance [$instance]")?.first
        } catch (e: SnapshotFormatException) {
            // Abnormal, unlike a missing record (an instance of the previous preview writes none): named, with why. The
            // read is shared for five seconds on a control host, which bounds the line to that.
            log.warn {
                "The resident documents of instance [$instance] do not read: propagation counts it unknown " +
                    "(${e.message})."
            }
            return null
        } ?: return null
        check(resident.instance == instance) { "The resident documents under [$instance] are ${resident.instance}'s" }
        return resident
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

    /**
     * Each listed instance's report, or that instance named unreadable when its report is missing, does not decode, or
     * is another's (`CTL-56`). Anything else, a store that does not answer above all, fails the whole call.
     */
    override suspend fun readings(window: String): ShadowReadings {
        val instances = read(reporters, window)?.first.orEmpty()
        check(
            instances.all(ShadowReport::isInstanceId)
        ) { "The reporters of window $window name an invalid instance id" }
        val read = coroutineScope {
            instances.sorted().map { instance -> async { instance to reading(window, instance) } }.awaitAll()
        }
        return ShadowReadings(
            read.mapNotNull { it.second },
            read.filter { it.second == null }.mapTo(HashSet()) { it.first }
        )
    }

    /** [instance]'s report of [window], or null when it is listed and cannot be read; a store failure is thrown. */
    private suspend fun reading(window: String, instance: String): ShadowReport? {
        val key = ShadowCacheSpecs.reportKey(window, instance)
        val report = try {
            read(reports, key)?.first
        } catch (e: SnapshotFormatException) {
            log.warn(e) { "[$key] does not read: instance $instance's evidence on window $window is missing." }
            return null
        }
        if (report == null) {
            log.warn { "[$key] is listed as reported but has no report: instance $instance's evidence is missing." }
            return null
        }
        if (report.window != window || report.instance != instance) {
            log.warn { "The report under [$key] is ${report.instance}'s of ${report.window}: missing evidence." }
            return null
        }
        return report
    }

    private fun checkOwner(report: ShadowReport, window: String, instance: String) {
        check(report.window == window && report.instance == instance) {
            "The report under [${ShadowCacheSpecs.reportKey(
                window,
                instance
            )}] is ${report.instance}'s of ${report.window}"
        }
    }

    override suspend fun statuses(): List<InstanceStatus> = readStatuses(lenient = false).statuses

    /**
     * Every listed status that reads and is recent, and the instances whose status does not decode, among those written
     * as recently (`CTL-73`). An undecodable status has no `at` anyone can read, so its key says when it was written:
     * every status is written with the key's exact life, [ShadowCacheSpecs.STATUS_TTL] (an hour, renewed by each write,
     * no jitter), so a key with less than that life minus a minute left was last written more than a minute ago, and
     * its instance is not live, as a decodable status of that age is not. The age comes from the store's clock, not
     * this reader's. A value with no life (written by hand, never by CoSec), or a store built without [statusKey],
     * counts only while its instance's entry in the list is current ([ShadowCacheSpecs.INDEX_EXPIRY]), which a gone
     * instance no longer refreshes. Each counted status is logged with why, at most once a minute per instance. A
     * status under another instance's key is the store's data being wrong, not a version the reader does not know, and
     * fails the call as [statuses] does; so does a store that does not answer.
     */
    override suspend fun statusReadings(): StatusReadings = readStatuses(lenient = true)

    /** Every listed status that reads and was written within [ShadowReportReader.READERS_TTL], and those that do not decode. */
    override suspend fun readerReadings(): StatusReadings =
        readStatuses(lenient = true, within = ShadowReportReader.READERS_TTL)

    private suspend fun readStatuses(lenient: Boolean, within: Duration = ShadowReportReader.STATUS_TTL): StatusReadings {
        val now = clock.instant()
        val since = now.minus(within)
        val index = read(instances, ShadowCacheSpecs.INDEX_KEY)?.first.orEmpty()
        val members = index.keys
        check(members.all(ShadowReport::isInstanceId)) { "The list of instances names an invalid instance id" }
        val found = members.sorted().chunked(READ_BATCH).flatMap { batch ->
            coroutineScope {
                batch.map { instance -> async { instance to status(instance, lenient) } }.awaitAll()
            }
        }
        val statuses = found.mapNotNull { (_, read) -> (read as? StatusRead.Read)?.status }
        val unreadable = HashSet<String>()
        for ((instance, read) in found) {
            if (read !is StatusRead.Unreadable) continue
            val written = statusKey?.let { key -> writtenWithin(key(instance), within) }
            if (written ?: current(index[instance], now)) {
                warnUnreadable(instance, read.error)
                unreadable += instance
            }
        }
        return StatusReadings(statuses.filter { !it.at.isBefore(since) }, unreadable)
    }

    private sealed interface StatusRead {
        class Read(val status: InstanceStatus) : StatusRead

        class Unreadable(val error: SnapshotFormatException) : StatusRead
    }

    /**
     * [instance]'s status: null when it has none (it expired), [StatusRead.Unreadable] when [lenient] and it does not
     * decode.
     */
    private suspend fun status(instance: String, lenient: Boolean): StatusRead? {
        val status = try {
            read(statuses, instance, "The status of instance [$instance]")?.first
        } catch (e: SnapshotFormatException) {
            if (!lenient) throw e
            return StatusRead.Unreadable(e)
        } ?: return null
        check(status.instance == instance) { "The status under [$instance] is ${status.instance}'s" }
        if (warned.isNotEmpty()) warned.remove(instance)
        return StatusRead.Read(status)
    }

    /** Whether an instance listed at [listedAt] still has a current entry in the list ([ShadowCacheSpecs.INDEX_EXPIRY]). */
    private fun current(listedAt: Instant?, now: Instant): Boolean =
        listedAt != null && now.isBefore(listedAt.plus(ShadowCacheSpecs.INDEX_EXPIRY))

    /** Logs [instance]'s undecodable status at most once per [ShadowReportReader.STATUS_TTL]; the meter counts each. */
    private fun warnUnreadable(instance: String, error: SnapshotFormatException) {
        val now = clock.instant()
        val last = warned[instance]
        if (last != null && now.isBefore(last.plus(ShadowReportReader.STATUS_TTL))) return
        val claimed = if (last == null) {
            warned.putIfAbsent(instance, now) == null
        } else {
            warned.replace(instance, last, now)
        }
        if (!claimed) return
        log.warn {
            "The status of instance [$instance] does not read: it counts as live and acknowledging no shadow window " +
                "until it reads, or until a minute after its last write, which its key's remaining life tells " +
                "(${error.message})."
        }
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

    private suspend fun <V : Any> read(cache: Store<String, V>, key: String, name: String = "[$key]") =
        cache.readRecord(key, name)

    @InternalCoSecApi
    companion object {
        private val log = KotlinLogging.logger {}
        private const val MAX_ATTEMPTS = 16

        /** How many statuses [statuses] reads at once. */
        private const val READ_BATCH = 64

        /** Creates the report caches under [keyPrefix] through [factory], with CoSec's locks ([CoSecCacheSpecs.locks]). */
        @InternalCoSecApi
        fun create(
            factory: CoCacheFactory,
            keyPrefix: String = DEFAULT_KEY_PREFIX,
            clock: Clock = Clock.systemUTC()
        ): CoCacheShadowReports {
            val statuses = ShadowCacheSpecs.statuses(keyPrefix)
            val statusStore = factory.createLocked(statuses)
            // The keyspace the store was created in, after every layer: where its keys and their lives are.
            val admin = checkNotNull(
                factory.runtime.admin(statuses.name)
            ) { "The status store ${statuses.name} was not created." }
            val keys = factory.runtime.backend.let { it.readOnlyStore(admin.keyspace) ?: it.store(admin.keyspace) }
            return CoCacheShadowReports(
                factory.createLocked(ShadowCacheSpecs.reports(keyPrefix)),
                factory.createLocked(ShadowCacheSpecs.reporters(keyPrefix)),
                statusStore,
                factory.createLocked(ShadowCacheSpecs.instances(keyPrefix)),
                factory.createLocked(ShadowCacheSpecs.residents(keyPrefix)),
                clock,
            ) { instance -> keys.read(admin.keyspace.storageKey(instance), null, null, null) }
        }
    }
}

/**
 * Whether a status key, as [read] answers it, was last written within [within], from the life it has left (`CTL-73`):
 * false when it is gone; null when that cannot be told (a key with no life).
 */
private fun writtenWithin(read: L1Read, within: Duration): Boolean? = when (read) {
    is L1Read.Hit -> read.ttl?.let { left -> left >= ShadowCacheSpecs.STATUS_TTL.minus(within) }
    is L1Read.Miss, is L1Read.Leased -> false
}
