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

package me.ahoo.cosec.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.explain.DecisionRef

enum class ShadowDiffKind {
    /** Denied today, allowed by the candidates: the change grants access. Review before enforcing. */
    WIDENED,

    /** Allowed today, denied by the candidates: the change revokes access. */
    NARROWED,

    /** Same result, decided by a different rule. Reported only when asked for. */
    RULE_CHANGED,

    /** The candidates failed to evaluate (e.g. a matcher threw); enforcing them would deny with an error. */
    CANDIDATE_ERROR,

    /**
     * The candidates read an external attribute the request did not fetch, and so are indeterminate (RFC 0006 §9.5.4):
     * a shadow never fetches one.
     */
    CANDIDATE_INDETERMINATE,
}

/**
 * One decision as the enforced policies and as the candidates make it.
 */
data class ShadowOutcome(
    val authorized: Boolean,
    val reason: String,
    val decidedBy: DecisionRef?,
    val error: String? = null
)

/**
 * A request on which the candidate policies decide differently from the enforced ones.
 */
data class ShadowDiff(
    val kind: ShadowDiffKind,
    val requestId: String,
    val tenantId: String,
    val principalId: String,
    val appId: String,
    val method: String,
    val path: String,
    val enforced: ShadowOutcome,
    val candidate: ShadowOutcome
)

/**
 * SPI for consuming shadow diffs. Called off the request thread; thrown exceptions are logged and dropped.
 */
fun interface ShadowEventSink {
    fun publish(diff: ShadowDiff)
}

/**
 * Logs diffs under `me.ahoo.cosec.shadow`: access changes and errors at WARN, rule changes at INFO.
 */
class LoggingShadowEventSink : ShadowEventSink {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun publish(diff: ShadowDiff) {
        if (diff.kind == ShadowDiffKind.RULE_CHANGED) {
            log.info { "Shadow $diff" }
        } else {
            log.warn { "Shadow $diff" }
        }
    }
}
