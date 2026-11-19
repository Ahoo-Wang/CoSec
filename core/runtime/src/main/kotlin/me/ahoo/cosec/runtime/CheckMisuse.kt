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

package me.ahoo.cosec.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The checks [AuthorizationChecker] refuses because of how it was called, not because of what the documents say
 * (UA-5). Each is still `Deny(null)`, fail closed and documented; logging them is what keeps "a policy is missing"
 * from being the reader's first guess. Every occurrence is logged at `DEBUG`; each [Kind] at `WARN` at most once per
 * [interval], so a loop of checks with the same mistake never floods the log.
 */
internal class CheckMisuse(
    private val interval: Duration = DEFAULT_INTERVAL,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** What made a check be refused before any document was read. */
    enum class Kind(val explanation: String) {
        FOREIGN_CONTEXT("the SecurityContext is not one the pipeline made (a mock, or one built by hand)"),
        ENDED_REQUEST("its request has ended: the context was kept after the response was written (PIP-26)"),
        MALFORMED_ACTION("the action is not `service:verb` or `http:<METHOD>` in upper case"),
    }

    private val lastWarnings = Kind.entries.associateWith { AtomicLong(NEVER) }

    /**
     * Records one refused check of [kind] for the request [requestId] (null when the context has none), of [action] on
     * [resource] as the caller wrote them. The message is built only when it is logged: a loop of refused checks with
     * DEBUG off allocates nothing for logging.
     */
    fun record(kind: Kind, requestId: String?, action: String, resource: String) {
        if (warns(kind)) {
            log.warn { message(kind, requestId, action, resource) }
        } else if (log.isDebugEnabled()) {
            log.debug { message(kind, requestId, action, resource) }
        }
    }

    /** As the other [record], for a check the engine's way: [action] and [resource] are rendered only when logged. */
    fun record(kind: Kind, requestId: String?, action: Action, resource: Resource) {
        if (warns(kind)) {
            log.warn { message(kind, requestId, action, resource) }
        } else if (log.isDebugEnabled()) {
            log.debug { message(kind, requestId, action, resource) }
        }
    }

    private fun message(kind: Kind, requestId: String?, action: Action, resource: Resource): String =
        message(kind, requestId, "${action.namespace}:${action.verb}", "${resource.type}/${resource.id}")

    private fun message(kind: Kind, requestId: String?, action: String, resource: String): String =
        "Check denied before any policy was read: ${kind.explanation}. Request [${requestId ?: "none"}], " +
            "action [$action] on [$resource]. See $PAGE"

    private fun warns(kind: Kind): Boolean {
        val last = lastWarnings.getValue(kind)
        val now = nanoTime()
        val previous = last.get()
        if (previous != NEVER && now - previous < interval.toNanos()) {
            return false
        }
        return last.compareAndSet(previous, now)
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(1)
        const val PAGE = "https://cosec.ahoo.me/operations/troubleshooting#a-check-answers-false"

        /** The one every checker shares by default: the bound is per process, however many checkers there are. */
        val SHARED = CheckMisuse()

        private const val NEVER = Long.MIN_VALUE
        private val log = KotlinLogging.logger(AuthorizationChecker::class.java.name)
    }
}
