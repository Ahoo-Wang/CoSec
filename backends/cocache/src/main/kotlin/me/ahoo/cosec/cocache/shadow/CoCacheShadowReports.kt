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

package me.ahoo.cosec.cocache.shadow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import java.time.Clock
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
 * status has expired (it is gone), as one older than a minute. A status or list that cannot be read fails it.
 */
@ExperimentalCoSecApi
class CoCacheShadowReports @InternalCoSecApi constructor(
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

    @InternalCoSecApi
    companion object {
        private const val MAX_ATTEMPTS = 16

        /** How many statuses [statuses] reads at once. */
        private const val READ_BATCH = 64

        /** Creates the report caches under [keyPrefix] through [factory], with CoSec's locks ([CoSecCacheSpecs.locks]). */
        @InternalCoSecApi
        fun create(
            factory: CoCacheFactory,
            keyPrefix: String = DEFAULT_KEY_PREFIX,
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
