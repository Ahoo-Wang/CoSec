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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.identity.IdentityRefusals
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.runtime.shadow.StagedDocuments

/**
 * The counters the starter registers beside the decision timer ([MetricsListener]): what the decision listeners did with
 * their events, the tokens accepted unchecked while revocations were unreadable, and the forwarded identities a service
 * in gateway mode refused, and how old the staged list this instance samples by is. One place for them, as [MicrometerSnapshotMetrics] is the one for the snapshot source's: each
 * counter reads a source the starter holds, so none is a listener, and none is for an application to register.
 */
@InternalCoSecApi
object CoSecMeters {
    const val LISTENER_TAG = "listener"
    const val LISTENER_DROPPED = "cosec.listener.dropped"
    const val LISTENER_FAILURES = "cosec.listener.failures"
    const val AUDIT_REQUIRED_FAILURES = "cosec.audit.required.failures"
    const val UNCHECKED_REVOCATIONS = "cosec.revocation.unchecked"
    const val IDENTITY_REFUSED = "cosec.identity.refused"
    const val LOCAL_RATE_LIMIT_REFUSED = "cosec.ratelimit.local.refused"
    const val DOCUMENT_TAG = "document"
    const val SHADOW_STAGED_AGE = "cosec.shadow.staged.age"

    /**
     * Registers what [listeners] count (§9.5.2): `cosec.listener.dropped` and `cosec.listener.failures` by listener,
     * and `cosec.audit.required.failures`.
     */
    fun bind(registry: MeterRegistry, listeners: DecisionListeners) {
        for (stats in listeners.stats) {
            FunctionCounter.builder(LISTENER_DROPPED, stats) { it.dropped.toDouble() }
                .description("Decision events a full queue dropped")
                .tag(LISTENER_TAG, stats.listener)
                .register(registry)
            FunctionCounter.builder(LISTENER_FAILURES, stats) { it.failures.toDouble() }
                .description("Decision listener calls that failed or timed out")
                .tag(LISTENER_TAG, stats.listener)
                .register(registry)
        }
        FunctionCounter.builder(AUDIT_REQUIRED_FAILURES, listeners) {
            it.requiredFailuresOnNonPermits.toDouble()
        }
            .description("REQUIRED listener failures on decisions they could not change")
            .register(registry)
    }

    /**
     * Registers `cosec.revocation.unchecked`: the tokens accepted without a revocation check because the revocation
     * store could not be read (PIP-7). While it rises, a revoked token is accepted.
     */
    fun bind(registry: MeterRegistry, unchecked: UncheckedRevocations) {
        FunctionCounter.builder(UNCHECKED_REVOCATIONS, unchecked) { it.count.toDouble() }
            .description("Tokens accepted without a revocation check, the revocation store unreadable")
            .register(registry)
    }

    /**
     * Registers `cosec.ratelimit.local.refused`, by the kind of document whose rule asked (R2D-3): the new buckets the
     * local limiter could not admit, because the rule's, its document's or the whole bound was full. Each one is a
     * request a strict rule answered `503` or a lenient one did not limit; any non-zero rate is a rule whose key clients
     * vary, or bounds too small for the keys in use.
     */
    fun bind(registry: MeterRegistry, limiter: LocalRateLimiter) {
        LocalRateLimiter.Refused.entries.forEach { kind ->
            FunctionCounter.builder(LOCAL_RATE_LIMIT_REFUSED, limiter) { it.refused(kind).toDouble() }
                .description("New rate limit buckets the local limiter could not admit")
                .tag(DOCUMENT_TAG, kind.tag)
                .register(registry)
        }
    }

    /**
     * Registers `cosec.identity.refused`, one series per problem type the gate refuses with (`OBS-7`): the forwarded
     * identities a service in gateway mode refused. The tag's values are that closed set, never anything a request
     * carries. Any non-zero rate is a hop that is misconfigured or a request that did not come through the gateway.
     */
    fun bind(registry: MeterRegistry, refusals: IdentityRefusals) {
        IdentityRefusals.TYPES.forEach { type ->
            FunctionCounter.builder(IDENTITY_REFUSED, refusals) { it.count(type).toDouble() }
                .tag(MetricsListener.PROBLEM_TAG, type.slug)
                .description("Forwarded identities a service in gateway mode refused, by problem")
                .register(registry)
        }
    }

    /**
     * Registers `cosec.shadow.staged.age`, in seconds (`OBS-8`): how long ago this instance last read the staged list it
     * samples by, NaN before the first read. About `report-interval` while the store answers; rising, the list cannot be
     * read, so the instance reads candidates by their keys and its windows are not acknowledged.
     */
    fun bind(registry: MeterRegistry, staged: StagedDocuments) {
        Gauge.builder(SHADOW_STAGED_AGE, staged) { it.age()?.toNanos()?.div(NANOS_PER_SECOND) ?: Double.NaN }
            .description("Seconds since this instance last read the staged candidate list")
            .baseUnit("seconds")
            .register(registry)
    }

    private const val NANOS_PER_SECOND = 1e9
}
