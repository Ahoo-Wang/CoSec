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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/**
 * Tuning of a [SnapshotSource]. Defaults are the values RFC 0003 §12 decided (Q7, Q18, Q19); the ones marked there as
 * "confirmed after measurement" may change once §4.8 has real numbers.
 */
// stays: one parameter per option of a value set by name
@Suppress("LongParameterList")
@InternalCoSecApi
class SnapshotOptions(
    /**
     * How often a pass starts that revalidates every installed document with a conditional read; while the previous
     * pass is still in flight, the next starts as soon as it ends (#1215). A change whose invalidation was lost, or whose
     * reload failed, is installed by the first pass that reaches its document after the change is readable (`SNP-6`).
     * At least [MIN_REVALIDATE_INTERVAL], at most [MAX_REVALIDATE_INTERVAL], less than [lagWarn], and less than
     * [failClosedMaxLag] when [failClosedTenants] are set.
     */
    val revalidateInterval: Duration = Duration.ofSeconds(10),
    /** Lag above which a document is logged at WARN and reported to [SnapshotMetrics.onLagExceeded]. */
    val lagWarn: Duration = Duration.ofSeconds(60),
    /** Lag above which a document is logged at ERROR and reported as stale. */
    val lagEscalate: Duration = Duration.ofMinutes(5),
    /**
     * Tenants whose requests fail when the snapshot they use lags more than [failClosedMaxLag] (RFC 0001 §7.1);
     * [ALL_TENANTS] names every tenant (R2D-5). Empty by default: an installed document keeps deciding however old.
     */
    val failClosedTenants: Set<String> = emptySet(),
    val failClosedMaxLag: Duration = Duration.ofMinutes(30),
    /** How long [SnapshotSource.load] waits for a cold document before it answers with a failed one. */
    val coldLoadTimeout: Duration = Duration.ofSeconds(2),
    /** The most app documents held at once; the least recently used one is evicted beyond it. */
    val maxApps: Int = DEFAULT_MAX_APPS,
    /**
     * The most tenant documents held at once (RFC 0004 Q15); the least recently used one is evicted beyond it. Sized so
     * that an instance holds the tenants it serves: a tenant document dropped is read and compiled again on its next
     * request.
     */
    val maxTenants: Int = DEFAULT_MAX_TENANTS,
    /**
     * The most entries for app ids the store holds no document for, apart from [maxApps]: app ids a client chooses never
     * evict an app document that exists (`SNP-7`, RC1-7). The least recently used one goes beyond it.
     */
    val maxAbsentApps: Int = DEFAULT_MAX_ABSENT_APPS,
    /**
     * The most entries for tenant ids the store holds no document for, apart from [maxTenants] (`SNP-7`): every request
     * of a tenant without a document reads its entry, so it is sized for every such tenant an instance serves. Tenant ids
     * come from credentials, so clients cannot flood it as they can the app family's.
     */
    val maxAbsentTenants: Int = DEFAULT_MAX_ABSENT_TENANTS,
    val compileThreads: Int = 2,
    /** Compilations waiting for a thread; beyond it a load fails closed (RFC 0003 §6 F8). */
    val compileQueueCapacity: Int = 64,
    /** Documents reloaded at once by a revalidation pass or after a reset ([DEFAULT_RELOAD_CONCURRENCY]). */
    val reloadConcurrency: Int = DEFAULT_RELOAD_CONCURRENCY,
    val prewarmInitialBackoff: Duration = Duration.ofSeconds(1),
    val prewarmMaxBackoff: Duration = Duration.ofSeconds(30),
    /** The least time between two lag reports (log line and metric) for the same document. */
    val lagLogInterval: Duration = Duration.ofMinutes(1),
    /** How long a store read may take before the load treats it as failed; bounds stores whose futures never complete. */
    val storeReadTimeout: Duration = Duration.ofSeconds(30),
    /** How long a reload waits before it retries after the compile queue was full. */
    val rejectedReloadRetry: Duration = Duration.ofMillis(200),
) {

    init {
        revalidateIntervalProblem(revalidateInterval, lagWarn, failClosedTenants, failClosedMaxLag)?.let {
            throw IllegalArgumentException("revalidateInterval $it.")
        }
        require(lagWarn <= lagEscalate) { "lagWarn must not exceed lagEscalate." }
        require(!failClosedMaxLag.isNegative) { "failClosedMaxLag must not be negative." }
        require((!coldLoadTimeout.isNegative && !coldLoadTimeout.isZero)) { "coldLoadTimeout must be positive." }
        require(maxApps > 0) { "maxApps must be positive." }
        require(maxTenants > 0) { "maxTenants must be positive." }
        require(maxAbsentApps > 0) { "maxAbsentApps must be positive." }
        require(maxAbsentTenants > 0) { "maxAbsentTenants must be positive." }
        require(compileThreads > 0) { "compileThreads must be positive." }
        require(compileQueueCapacity > 0) { "compileQueueCapacity must be positive." }
        require(reloadConcurrency > 0) { "reloadConcurrency must be positive." }
        require(!storeReadTimeout.isNegative && !storeReadTimeout.isZero) { "storeReadTimeout must be positive." }
        require(
            !rejectedReloadRetry.isNegative && !rejectedReloadRetry.isZero
        ) { "rejectedReloadRetry must be positive." }
        require(
            !prewarmInitialBackoff.isNegative && !prewarmInitialBackoff.isZero && prewarmInitialBackoff <= prewarmMaxBackoff
        ) {
            "prewarmInitialBackoff must be positive and must not exceed prewarmMaxBackoff."
        }
    }

    @InternalCoSecApi
    companion object {
        /**
         * The longest [revalidateInterval] (RC1-50): the passes that install a change whose invalidation was lost start
         * at least this often, unless the one before is still in flight.
         */
        val MAX_REVALIDATE_INTERVAL: Duration = Duration.ofMinutes(5)

        /**
         * The shortest [revalidateInterval]: every pass reads every installed document from the store, so a shorter one
         * would keep Redis busy for nothing (review L1 of #1200), and below a millisecond the schedule cannot run at all.
         */
        val MIN_REVALIDATE_INTERVAL: Duration = Duration.ofSeconds(1)

        const val DEFAULT_MAX_APPS: Int = 1024

        /**
         * A pass takes about the entries held × one conditional read ÷ this: at 80,000 entries and 1 ms a read, 25 s at 4,
         * 6.3 s at 16 (measured, beta.5). At 16 a pass of the default bounds' 81,920 entries stays within the default
         * interval for reads up to about 2 ms; the reads per second over an interval do not change, only their burst.
         */
        const val DEFAULT_RELOAD_CONCURRENCY: Int = 16

        /** RFC 0004 Q15's 1,024 until beta.5; raised so that an instance holds the tenants it serves (SaaS at the gateway). */
        const val DEFAULT_MAX_TENANTS: Int = 16_384

        /** The flooding bound of `SNP-7`: app ids come from requests, so a client can name as many as it likes. */
        const val DEFAULT_MAX_ABSENT_APPS: Int = 256

        /** About 30 MB at 465 bytes an entry when every place is used. */
        const val DEFAULT_MAX_ABSENT_TENANTS: Int = 65_536

        /**
         * Why [revalidateInterval] cannot be used with these lag settings, or null when it can (`SNP-6`): the periodic
         * pass is the only bound on a lost invalidation, so it cannot be turned off, and a lag report must mean a
         * missed pass, not the schedule. The host names its own property in the error.
         */
        /** In [failClosedTenants]: every tenant fails closed past [failClosedMaxLag] (R2D-5). */
        const val ALL_TENANTS = "*"

        @InternalCoSecApi
        fun revalidateIntervalProblem(
            revalidateInterval: Duration,
            lagWarn: Duration,
            failClosedTenants: Set<String>,
            failClosedMaxLag: Duration,
        ): String? = when {
            revalidateInterval.isNegative || revalidateInterval.isZero ->
                "must be positive: the periodic revalidation cannot be turned off"

            revalidateInterval < MIN_REVALIDATE_INTERVAL ->
                "must be at least $MIN_REVALIDATE_INTERVAL: each pass reads every installed document from the store"

            revalidateInterval > MAX_REVALIDATE_INTERVAL -> "must be at most $MAX_REVALIDATE_INTERVAL"
            revalidateInterval >= lagWarn -> "must be less than the lag warning threshold ($lagWarn)"
            failClosedTenants.isNotEmpty() && revalidateInterval >= failClosedMaxLag ->
                "must be less than the fail-closed maximum lag ($failClosedMaxLag)"

            else -> null
        }
    }
}
