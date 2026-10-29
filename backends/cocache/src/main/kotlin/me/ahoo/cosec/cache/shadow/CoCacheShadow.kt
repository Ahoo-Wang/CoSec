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
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
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

/**
 * The caches of the shadow window (RFC 0007 §6.4, §6.5):
 *
 * | Cache | Redis key | Policy |
 * |---|---|---|
 * | candidates | `<p>:candidate:<docKey>` | store of record: the control plane's only copy while a change stages |
 * | reports | `<p>:shadow:report:<window>:<instance>` | one instance's report of one window; expires [REPORT_TTL] after its last write |
 * | reporters | `<p>:shadow:reporters:<window>` | the instances that reported on one window, only ever added to; expires as the reports |
 * | statuses | `<p>:shadow:status:instances` | every instance's last status; expires [STATUS_TTL] after the last write |
 *
 * Reports and statuses are observations, so they expire; a failed read still throws, so that an outage is never read
 * as "no samples" or "no instance". Each instance writes only its own report key (round-2 review R1), so that one
 * instance can never replace another's numbers, and an ACL can confine each instance to its own keys.
 */
object ShadowCacheSpecs {
    /** Longer than the longest window (24 hours) and the week a change may stay unpublished. */
    val REPORT_TTL: Duration = Duration.ofDays(8)

    /** Statuses older than a minute are not counted (RFC 0007 §6.5); the key outlives that only to be read. */
    val STATUS_TTL: Duration = Duration.ofMinutes(2)

    const val STATUS_KEY: String = "instances"

    fun candidate(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<SnapshotKey, StoredCandidate> =
        CacheSpec.builder<SnapshotKey, StoredCandidate>(CoSecCacheSpecs.CANDIDATE_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.CANDIDATE_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(StoredCandidateCodec)
            .policy(policy.storeOfRecord())
            .build()

    fun reports(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<String, ShadowReport> =
        CacheSpec.builder<String, ShadowReport>(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(ShadowReportCodec)
            .policy(policy.toBuilder().ttl(REPORT_TTL).readFailure(ReadFailureMode.THROW).build())
            .build()

    fun reporters(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<String, Set<String>> =
        CacheSpec.builder<String, Set<String>>(CoSecCacheSpecs.SHADOW_REPORTER_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.SHADOW_REPORTER_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(ReportersCodec)
            .policy(policy.toBuilder().ttl(REPORT_TTL).readFailure(ReadFailureMode.THROW).build())
            .build()

    /** The key of [instance]'s report of [window], under the reports' namespace. */
    fun reportKey(window: String, instance: String): String = "$window:$instance"

    fun statuses(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<String, Map<String, InstanceStatus>> =
        CacheSpec.builder<String, Map<String, InstanceStatus>>(CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(InstanceStatusesCodec)
            .policy(policy.toBuilder().ttl(STATUS_TTL).readFailure(ReadFailureMode.THROW).build())
            .build()
}

object StoredCandidateCodec : Codec<StoredCandidate> {
    override fun encode(value: StoredCandidate): ByteArray = ShadowCodec.encode(value)

    override fun decode(bytes: ByteArray): StoredCandidate = ShadowCodec.decodeCandidate(bytes)
}

object ShadowReportCodec : Codec<ShadowReport> {
    override fun encode(value: ShadowReport): ByteArray = ShadowCodec.encodeReport(value)

    override fun decode(bytes: ByteArray): ShadowReport = ShadowCodec.decodeReport(bytes)
}

/** The instances that reported on a window, as a sorted JSON array of their ids. */
object ReportersCodec : Codec<Set<String>> {
    override fun encode(value: Set<String>): ByteArray = CoSecJson.mapper.writeValueAsBytes(value.sorted())

    override fun decode(bytes: ByteArray): Set<String> {
        val node = CoSecJson.mapper.readTree(bytes)
        require(
            node.isArray && node.values().all { it.isString }
        ) { "The reporters of a window are an array of instance ids" }
        return node.values().mapTo(LinkedHashSet()) { it.asString() }
    }
}

object InstanceStatusesCodec : Codec<Map<String, InstanceStatus>> {
    override fun encode(value: Map<String, InstanceStatus>): ByteArray = ShadowCodec.encodeStatuses(value)

    override fun decode(bytes: ByteArray): Map<String, InstanceStatus> = ShadowCodec.decodeStatuses(bytes)
}

/** The data plane's [CandidateStore] on Redis: reads `<p>:candidate:<docKey>` from Redis, never a local copy. */
class CoCacheCandidateStore(private val candidates: Cache<SnapshotKey, StoredCandidate>) : CandidateStore {
    override suspend fun candidate(key: SnapshotKey): StoredCandidate? =
        when (val read = candidates.async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value
            ConditionalGet.Unchanged -> error(
                "An unconditional read of the candidate of [$key] was answered as unchanged."
            )
            else -> null
        }

    companion object {
        fun create(runtime: CacheRuntime, keyPrefix: String = CoSec.COSEC): CoCacheCandidateStore =
            CoCacheCandidateStore(runtime.create(ShadowCacheSpecs.candidate(keyPrefix)))
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
 * before the control plane looks again is not detected. Statuses share one key, replaced with `putIfMatch` over the
 * value read, so that instances writing at once never lose each other's entries; statuses older than a minute are
 * dropped as they are written and ignored as they are read.
 */
class CoCacheShadowReports(
    private val reports: Cache<String, ShadowReport>,
    private val reporters: Cache<String, Set<String>>,
    private val statuses: Cache<String, Map<String, InstanceStatus>>,
    private val clock: Clock = Clock.systemUTC(),
) : ShadowReportSink, ShadowReportReader {
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
        val since = clock.instant().minus(ShadowReportReader.STATUS_TTL)
        update(statuses, ShadowCacheSpecs.STATUS_KEY) { current ->
            current.filterValues { !it.at.isBefore(since) } + (status.instance to status)
        }
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
        return read(statuses, ShadowCacheSpecs.STATUS_KEY)?.first?.values?.filter { !it.at.isBefore(since) }.orEmpty()
    }

    private suspend fun <V : Any> update(cache: Cache<String, V>, key: String, next: (V) -> V, empty: V) {
        repeat(MAX_ATTEMPTS) {
            val current = read(cache, key)
            val value = next(current?.first ?: empty)
            if (cache.async().putIfMatch(key, current?.second, value).await() is ConditionalPut.Written) {
                return
            }
        }
        error("[$key] kept changing while it was written")
    }

    private suspend fun update(
        cache: Cache<String, Map<String, InstanceStatus>>,
        key: String,
        next: (Map<String, InstanceStatus>) -> Map<String, InstanceStatus>
    ) =
        update(cache, key, next, emptyMap())

    private suspend fun <V : Any> read(cache: Cache<String, V>, key: String): Pair<V, me.ahoo.cocache.api.spi.Digest>? =
        when (val read = cache.async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value to read.digest
            ConditionalGet.Unchanged -> error("An unconditional read of [$key] was answered as unchanged.")
            else -> null
        }

    companion object {
        private const val MAX_ATTEMPTS = 16

        fun create(runtime: CacheRuntime, keyPrefix: String = CoSec.COSEC, clock: Clock = Clock.systemUTC()): CoCacheShadowReports =
            CoCacheShadowReports(
                runtime.create(ShadowCacheSpecs.reports(keyPrefix)),
                runtime.create(ShadowCacheSpecs.reporters(keyPrefix)),
                runtime.create(ShadowCacheSpecs.statuses(keyPrefix)),
                clock
            )
    }
}
