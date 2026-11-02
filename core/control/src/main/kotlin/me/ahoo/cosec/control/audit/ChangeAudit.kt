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

package me.ahoo.cosec.control.audit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** A revision as an event names it: its number and the digest it is addressed by, or a candidate's digest. */
data class RevisionRef(val revision: Long, val digest: String?)

/**
 * A head a published change was checked with that is not the same once it is published (RC1-33): its [document]
 * (`platform`, `app:<a>`, `tenant:<t>`, or `list:apps` for the app list), the digest it was checked with ([checked],
 * `null` for none) and the digest it has now ([current], `null` for none, or when it cannot be read: [readable]).
 */
data class MovedHead(val document: String, val checked: String?, val current: String?, val readable: Boolean = true)

/**
 * What happened to a change (RFC 0007 §10.5). Never carries a document's content: the change id, the document, the
 * base and candidate it was about, what needed approval, the confirmations, the shadow summary and the note; for
 * [Kind.HEADS_MOVED] and [Kind.REVIEW_NEEDED], the heads that [moved] and the escalations the analysis finds now.
 */
class ChangeEvent(
    val id: String,
    val time: Instant,
    val kind: Kind,
    val operator: Actor,
    val change: ChangeId,
    val document: SnapshotKey,
    val base: RevisionRef?,
    val candidate: RevisionRef?,
    val escalations: List<Escalation> = emptyList(),
    val confirmations: List<Confirmation> = emptyList(),
    val shadow: ShadowSummary? = null,
    val note: String = "",
    val moved: List<MovedHead> = emptyList(),
) {
    enum class Kind {
        PROPOSED,
        CHECKED,
        SHADOW_STARTED,
        SHADOWED,
        APPROVED,
        REJECTED,
        PUBLISHING,
        PUBLISHED,
        FAILED,
        BREAK_GLASS_REQUESTED,
        BREAK_GLASS_APPROVED,
        REVOKED,
        BOOTSTRAP,
        MIGRATE,

        /**
         * A change was published while another document it was checked with moved (RC1-33): the analysis, run again on
         * the current heads, finds nothing the publish did not have approved. Recorded, nothing to do.
         */
        HEADS_MOVED,

        /**
         * As [HEADS_MOVED], but the analysis run again finds an escalation the publish did not have approved, or could
         * not run: the published change may need a person's review (RC1-33). Nothing was rolled back.
         */
        REVIEW_NEEDED,
        ;

        /**
         * Whether the step waits for every REQUIRED listener before it takes effect (RFC 0007 §10.5, Q21): publishing,
         * and every step of creating break-glass (§8).
         */
        val gated: Boolean get() = this == PUBLISHING || this == BREAK_GLASS_REQUESTED || this == BREAK_GLASS_APPROVED
    }

    override fun toString(): String = "ChangeEvent($kind, change=${change.value}, document=$document, by=${operator.principal.value})"
}

/** The control plane's audit SPI (RFC 0007 §10.5); delivery as in RFC 0006 §9.4. */
@CoSecSpi
interface ChangeListener {
    val name: String

    val delivery: Delivery get() = Delivery.BEST_EFFORT

    suspend fun onChange(event: ChangeEvent)
}

/** A change step a REQUIRED listener could not record: the step did not happen (503). */
class ChangeAuditException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Delivers change events (RFC 0007 §10.5).
 *
 * - A [gated][ChangeEvent.Kind.gated] event is delivered to every REQUIRED listener, concurrently, before its step
 *   takes effect; one that fails or does not answer within [requiredTimeout] fails the step ([ChangeAuditException]),
 *   which then writes nothing.
 * - Every other event, and every event to a BEST_EFFORT listener, is delivered off the caller, and a failure is only
 *   logged: the revision record is already the durable record of what was published (Q21).
 */
class ChangeListeners(
    listeners: List<ChangeListener>,
    val requiredTimeout: Duration = 5.seconds,
) {
    private val required = listeners.filter { it.delivery == Delivery.REQUIRED }
    private val all = listeners
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(listeners.map { it.name }.toSet().size == listeners.size) { "Change listener names are unique." }
    }

    /** Whether a REQUIRED listener is configured: creating break-glass needs one (RFC 0007 §8). */
    val hasRequired: Boolean get() = required.isNotEmpty()

    suspend fun publish(event: ChangeEvent) {
        if (event.kind.gated && required.isNotEmpty()) {
            deliverRequired(event)
            all.filter { it.delivery != Delivery.REQUIRED }.forEach { later(it, event) }
        } else {
            all.forEach { later(it, event) }
        }
    }

    /**
     * Delivers [event] to every REQUIRED listener. A [CancellationException] is the caller's only while the caller's own
     * coroutine is cancelled; one a listener throws while the step is active (a cancelled future, say) is a failure of
     * that listener, so the step answers 503 like any other (RC1-31, as `DecisionListeners.deliver`).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliverRequired(event: ChangeEvent) {
        var failure: Exception? = null
        val delivered = try {
            withTimeoutOrNull(requiredTimeout) {
                coroutineScope { required.map { listener -> async { listener.onChange(event) } }.awaitAll() }
                true
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            failure = e
            null
        } catch (e: Exception) {
            failure = e
            null
        }
        if (delivered != true) {
            throw ChangeAuditException(
                "A REQUIRED change listener did not record $event${if (failure == null) " in time" else ""}.",
                failure
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun later(listener: ChangeListener, event: ChangeEvent) {
        scope.launch {
            try {
                listener.onChange(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Change listener [${listener.name}] could not record $event." }
            }
        }
    }

    /** Stops delivering the events still queued. */
    fun cancel() {
        scope.cancel()
    }

    companion object {
        val NONE: ChangeListeners = ChangeListeners(emptyList())
    }
}

/** Logs every change event (RFC 0007 §10.5); BEST_EFFORT. */
class LoggingChangeListener(override val name: String = "logging") : ChangeListener {
    override suspend fun onChange(event: ChangeEvent) {
        log.info {
            "Change ${event.change.value} ${event.kind} on ${event.document} by ${event.operator.principal.value}@" +
                "${event.operator.tenant.value}: base=${event.base}, candidate=${event.candidate}, " +
                "escalations=${event.escalations.size}, confirmations=${event.confirmations.size}"
        }
    }
}

/**
 * A change event as JSON (RFC 0007 §10.5), as an audit sink stores it: no document content, only what the event
 * carries.
 */
object ChangeEventJson {
    private val factory = tools.jackson.databind.node.JsonNodeFactory.instance

    fun write(event: ChangeEvent): String {
        val root = factory.objectNode()
            .put("id", event.id)
            .put("time", event.time.toString())
            .put("kind", event.kind.name)
            .put("change", event.change.value)
            .put("document", event.document.toString())
            .put("note", event.note)
        root.putObject(
            "operator"
        ).put("tenant", event.operator.tenant.value).put("principal", event.operator.principal.value)
        event.base?.let { root.putObject("base").put("revision", it.revision).put("digest", it.digest) }
        event.candidate?.let { root.putObject("candidate").put("revision", it.revision).put("digest", it.digest) }
        root.putArray("escalations").apply {
            event.escalations.forEach {
                addObject().put(
                    "subject",
                    it.subject
                ).put("reason", it.reason).put("detail", it.detail)
            }
        }
        root.putArray("confirmations").apply {
            event.confirmations.forEach {
                addObject().put(
                    "policy",
                    it.policy.value
                ).put("statement", it.statement).put("kind", it.kind).put("digest", it.digest)
            }
        }
        if (event.moved.isNotEmpty()) {
            root.putArray("moved").apply {
                event.moved.forEach {
                    addObject().put("document", it.document).put("checked", it.checked)
                        .put("current", it.current).put("readable", it.readable)
                }
            }
        }
        event.shadow?.let { shadow ->
            val node = root.putObject("shadow").put("closedAt", shadow.closedAt.toString())
                .put("insufficient", shadow.insufficient).put("blocked", shadow.blocked)
            node.putObject(
                "counts"
            ).apply { shadow.counts.toSortedMap().forEach { (outcome, count) -> put(outcome, count) } }
        }
        return me.ahoo.cosec.runtime.json.CoSecJson.mapper.writeValueAsString(root)
    }
}
