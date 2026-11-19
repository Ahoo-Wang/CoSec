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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.RequestSecurity
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * What a reader of this stack answers, in one place so the readers cannot drift (UA-7): the context CoSec's pipeline
 * made, or why there is none. Two cases stay apart (`PIP-27`): no context at all (CoSec's filter did not handle the
 * request), and a context under [SecurityContext.KEY] that the pipeline did not make, which is ignored. Code that lets
 * an absent context through would otherwise let a hand-made or mocked one through silently, so a throwing reader says
 * which, and a reader that answers null warns, at most once a minute for each receiver.
 */
internal object Unbound {
    enum class Receiver(val where: String, val instead: String) {
        REACTOR_CONTEXT(
            "in this Reactor context",
            "where the chain's context is not at hand, read the exchange: ServerWebExchanges.securityContextOrNull(exchange)",
        ),
        EXCHANGE(
            "on this exchange",
            "in a reactive chain, read the Reactor context: Mono.deferContextual { it.requireSecurityContext() } " +
                "(ReactiveSecurityContexts)",
        ),
    }

    private const val NEVER = Long.MIN_VALUE
    private val log = LoggerFactory.getLogger(ReactiveSecurityContexts::class.java)
    private val interval = Duration.ofMinutes(1).toNanos()
    private val lastWarnings = Receiver.entries.associateWith { AtomicLong(NEVER) }

    /** No context at all: what to do, and the stack's other reader. */
    fun missing(receiver: Receiver): String =
        "No SecurityContext ${receiver.where}: CoSec's filter did not handle this request (it is not registered, or " +
            "this code runs outside its chain). Register CoSec's WebFilter (the starter does), or ${receiver.instead}."

    /** A context bound that the pipeline did not make, ignored. */
    fun foreign(receiver: Receiver): String =
        "A SecurityContext is bound ${receiver.where} that CoSec's pipeline did not make, and it is ignored (PIP-27): " +
            "it reads as no context. In a test, bind one the pipeline made (PermittedRequest, " +
            "PipelineFixture.securityContext) with CoSecTestBindings; in production only CoSec's WebFilter binds it, " +
            "and a filter of your own must not put one under SecurityContext.KEY."

    private fun made(bound: Any?): SecurityContext? =
        (bound as? SecurityContext)?.takeIf { RequestSecurity.of(it) != null }

    /** [bound] as the pipeline's context, or null; a foreign one is warned of, bounded. */
    fun orNull(bound: Any?, receiver: Receiver): SecurityContext? {
        val context = made(bound)
        if (context == null && bound != null) {
            ignored(receiver)
        }
        return context
    }

    /** [bound] as the pipeline's context; [IllegalStateException] saying whether there is none or it is ignored. */
    fun required(bound: Any?, receiver: Receiver): SecurityContext =
        made(bound) ?: throw IllegalStateException(if (bound == null) missing(receiver) else foreign(receiver))

    private fun ignored(receiver: Receiver) {
        val last = lastWarnings.getValue(receiver)
        val previous = last.get()
        val now = System.nanoTime()
        if (previous != NEVER && now - previous < interval) return
        if (last.compareAndSet(previous, now) && log.isWarnEnabled) {
            log.warn(foreign(receiver))
        }
    }

    /** For tests: the next foreign context on each receiver warns again. */
    fun resetWarnings() = lastWarnings.values.forEach { it.set(NEVER) }
}
