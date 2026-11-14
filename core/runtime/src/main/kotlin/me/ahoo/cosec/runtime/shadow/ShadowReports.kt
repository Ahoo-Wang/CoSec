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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Instant

/** How a sampled request came out on the candidate, against the enforced documents (RFC 0007 §6.4). */
@ExperimentalCoSecApi
enum class ShadowOutcome {
    /** The same decision, by the same statement. */
    SAME,

    /** The enforced documents did not permit; the candidate does. */
    WIDENED,

    /** The enforced documents permitted; the candidate does not. */
    NARROWED,

    /** The candidate is indeterminate where the enforced documents were not, for a reason other than a missing attribute. */
    BECAME_INDETERMINATE,

    /** The candidate reads an external attribute the request did not fetch (RFC 0006 §9.5.4): a shadow never fetches. */
    CANDIDATE_INDETERMINATE,

    /** The candidate cannot be compiled or evaluated on this instance; it must not be published (RFC 0007 §6.3). */
    CANDIDATE_ERROR,

    /** The same result, decided by another statement. */
    BY_CHANGED,

    /** The enforced documents could not decide; the candidate denies. */
    BECAME_DENIED,

    /** The same permit, by the same statement, with other rate limit obligations. */
    OBLIGATIONS_CHANGED,

    /** The candidate's base is not what this instance has installed: not compared. */
    BASE_MISMATCH,
    ;

    /** Whether this outcome is a sample of the candidate; [BASE_MISMATCH] compares nothing. */
    val effective: Boolean get() = this != BASE_MISMATCH
}

/**
 * One sampled request (RFC 0007 §6.4): the fields a decision event has (RFC 0006 §9.2), and the statements that decided
 * on each side, never a header, a token or an attribute value.
 */
@ExperimentalCoSecApi
class ShadowSample(
    val tenant: String,
    val principal: String,
    val app: String,
    val space: String,
    val action: String,
    val resource: String,
    val enforcedBy: String?,
    val candidateBy: String?,
) {
    private fun fields(): List<Any?> = listOf(
        tenant,
        principal,
        app,
        space,
        action,
        resource,
        enforcedBy,
        candidateBy,
    )

    override fun equals(other: Any?): Boolean = other is ShadowSample && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "ShadowSample(${fields().joinToString()})"
}

/**
 * What one instance saw of one shadow window (RFC 0007 §6.4): counts by outcome, at most [MAX_SAMPLES] samples of each,
 * the base revisions it compared against, and the first and last sample times. Cumulative: each write replaces the
 * instance's previous report for the window.
 */
@ExperimentalCoSecApi
class ShadowReport(
    val window: String,
    val instance: String,
    val counts: Map<ShadowOutcome, Long>,
    val samples: Map<ShadowOutcome, List<ShadowSample>>,
    val baseRevisions: Set<Long>,
    val first: Instant?,
    val last: Instant?,
) {
    private fun fields(): List<Any?> = listOf(window, instance, counts, samples, baseRevisions, first, last)

    override fun equals(other: Any?): Boolean = other is ShadowReport && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "ShadowReport(${fields().joinToString()})"

    /** These values with some replaced: for CoSec's own use. */
    @InternalCoSecApi
    @Suppress("LongParameterList")
    fun copy(
        window: String = this.window,
        instance: String = this.instance,
        counts: Map<ShadowOutcome, Long> = this.counts,
        samples: Map<ShadowOutcome, List<ShadowSample>> = this.samples,
        baseRevisions: Set<Long> = this.baseRevisions,
        first: Instant? = this.first,
        last: Instant? = this.last,
    ): ShadowReport = ShadowReport(
        window = window,
        instance = instance,
        counts = counts,
        samples = samples,
        baseRevisions = baseRevisions,
        first = first,
        last = last
    )

    companion object {
        const val MAX_SAMPLES: Int = 20

        private val INSTANCE_ID = Regex("[A-Za-z0-9._-]+")

        /**
         * Whether [id] may name an instance: letters, digits, `.`, `_` and `-` only (round-2 review). An instance id
         * ends its report's Redis key, so a `:` in it would let one instance's key, or an ACL pattern written for it,
         * match another's.
         */
        fun isInstanceId(id: String): Boolean = INSTANCE_ID.matches(id)
    }
}
