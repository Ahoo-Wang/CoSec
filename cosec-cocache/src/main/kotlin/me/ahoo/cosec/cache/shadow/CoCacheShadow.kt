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

package me.ahoo.cosec.cache.shadow

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.runtime.shadow.InstanceStatus
import me.ahoo.cosec.runtime.shadow.ShadowCodec
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.runtime.shadow.ShadowReportSink
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration

/**
 * The caches of the shadow window (RFC 0007 §6.4, §6.5):
 *
 * | Cache | Redis key | Policy |
 * |---|---|---|
 * | candidates | `<p>:candidate:<docKey>` | store of record: the control plane's only copy while a change stages |
 * | reports | `<p>:shadow:report:<window>` | every instance's report of one window; expires [REPORT_TTL] after the last write |
 * | statuses | `<p>:shadow:status:instances` | every instance's last status; expires [STATUS_TTL] after the last write |
 *
 * Reports and statuses are observations, so they expire; a failed read still throws, so that an outage is never read
 * as "no samples" or "no instance".
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

    fun reports(keyPrefix: String = CoSec.COSEC, policy: CachePolicy = CachePolicy.DEFAULT): CacheSpec<String, Map<String, ShadowReport>> =
        CacheSpec.builder<String, Map<String, ShadowReport>>(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME)
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(ShadowReportsCodec)
            .policy(policy.toBuilder().ttl(REPORT_TTL).readFailure(ReadFailureMode.THROW).build())
            .build()

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

object ShadowReportsCodec : Codec<Map<String, ShadowReport>> {
    override fun encode(value: Map<String, ShadowReport>): ByteArray = ShadowCodec.encodeReports(value)

    override fun decode(bytes: ByteArray): Map<String, ShadowReport> = ShadowCodec.decodeReports(bytes)
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
 * Shadow reports and instance statuses on Redis (RFC 0007 §6.4, §6.5): one key per window holding every instance's
 * report, and one key holding every instance's status, each replaced with `putIfMatch` over the value it read, so that
 * instances writing at once never lose each other's entries. Statuses older than a minute are dropped as they are
 * written and ignored as they are read.
 */
class CoCacheShadowReports(
    private val reports: Cache<String, Map<String, ShadowReport>>,
    private val statuses: Cache<String, Map<String, InstanceStatus>>,
    private val clock: Clock = Clock.systemUTC(),
) : ShadowReportSink, ShadowReportReader {
    override suspend fun write(report: ShadowReport) {
        update(reports, report.window) { it + (report.instance to report) }
    }

    override suspend fun write(status: InstanceStatus) {
        val since = clock.instant().minus(ShadowReportReader.STATUS_TTL)
        update(statuses, ShadowCacheSpecs.STATUS_KEY) { current ->
            current.filterValues { !it.at.isBefore(since) } + (status.instance to status)
        }
    }

    override suspend fun reports(window: String): List<ShadowReport> = read(
        reports,
        window
    )?.first?.values?.toList().orEmpty()

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

    private suspend fun update(cache: Cache<String, Map<String, ShadowReport>>, key: String, next: (Map<String, ShadowReport>) -> Map<String, ShadowReport>) =
        update(cache, key, next, emptyMap())

    @JvmName("updateStatuses")
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
                runtime.create(ShadowCacheSpecs.statuses(keyPrefix)),
                clock
            )
    }
}
