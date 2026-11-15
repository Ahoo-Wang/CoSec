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

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant

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

    fun candidate(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<SnapshotKey, StoredCandidate> =
        CoSecCacheSpecs.storeOfRecord(
            CoSecCacheSpecs.CANDIDATE_CACHE_NAME,
            keyPrefix,
            SnapshotKeyEncoder,
            StoredCandidateCodec
        )
            .build()

    fun reports(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, ShadowReport> =
        observation(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME, keyPrefix, ShadowReportCodec, REPORT_TTL)

    fun reporters(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, Set<String>> =
        observation(CoSecCacheSpecs.SHADOW_REPORTER_CACHE_NAME, keyPrefix, ReportersCodec, REPORT_TTL)

    /** The key of [instance]'s report of [window], under the reports' namespace. */
    fun reportKey(window: String, instance: String): String = "$window:$instance"

    /** One instance's status under its id ([ShadowReport.isInstanceId]). */
    fun statuses(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, InstanceStatus> =
        observation(CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, keyPrefix, InstanceStatusCodec, STATUS_TTL)

    /** The instances that have a status to read, by when each last listed itself, under [INDEX_KEY]. */
    fun instances(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, Map<String, Instant>> =
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
