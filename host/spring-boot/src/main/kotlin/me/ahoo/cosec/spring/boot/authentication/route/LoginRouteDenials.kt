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

package me.ahoo.cosec.spring.boot.authentication.route

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Tells the operator when a document refuses a login route the starter serves: CoSec decides that route as every route,
 * and its request is anonymous (`PIP-6`), so a refused route refuses every refresh (`401`) and every client signs out
 * when its access token expires. Read from real traffic, BEST_EFFORT, off the request path: a refusal decided by the
 * documents (an implicit deny, a matching DENY, the tenant's platform boundary; never a 429, an indeterminate or an
 * invalid request) logs one WARN per route per [interval] that names the property, the path and which refusal it was,
 * and is kept for [LoginRoutesHealthIndicator]. A later permit clears it. It never fails readiness: a document can
 * change at any time, and it is the traffic that says.
 *
 * @param routes the property that sets each route, by the path it serves
 */
@InternalCoSecApi
class LoginRouteDenials(
    private val routes: Map<String, String>,
    private val interval: Duration = Duration.ofMinutes(1),
    private val clock: Clock = Clock.systemUTC(),
) : DecisionListener {
    /** When a route was last refused, and why. */
    @InternalCoSecApi
    class Denial(val since: Instant, val why: String)

    override val name: String = "cosecLoginRouteDenials"

    override val delivery: Delivery = Delivery.BEST_EFFORT

    private val denied = ConcurrentHashMap<String, Denial>()
    private val warned = ConcurrentHashMap<String, Instant>()

    override suspend fun onDecision(event: DecisionEvent) = record(
        event.request.method,
        event.request.path,
        event.outcome.reason
    )

    /** One decision on [path]: a permit clears the route, a refusal by the documents is recorded and told. */
    internal fun record(method: String, path: String, reason: OutcomeSummary.Reason) {
        if (method != POST) return
        val property = routes[path] ?: return
        if (reason == OutcomeSummary.Reason.PERMIT) {
            denied.remove(property)
            return
        }
        val refusal = refusalOf(reason) ?: return
        val now = clock.instant()
        denied[property] = Denial(now, refusal.why)
        val last = warned[property]
        if (last != null && last.plus(interval).isAfter(now)) return
        if (warned.put(property, now) != last) return
        log.warn {
            "$property=$path: ${refusal.why}, so CoSec refuses it. Every refresh and logout through it fails, and " +
                "clients sign out when their access token expires. ${refusal.fix}"
        }
    }

    /** The properties whose route was refused at its last decision, with when and why. */
    fun denied(): Map<String, Denial> = denied.toMap()

    /** A refusal the documents decided, and what the operator changes for it. */
    internal class Refusal(val why: String, val fix: String)

    internal companion object {
        const val POST = "POST"

        /**
         * What a refusal of [reason] says, or null when the documents did not decide it. A scope rejection is a
         * membership's answer, and an anonymous request without a space never gets one.
         */
        fun refusalOf(reason: OutcomeSummary.Reason): Refusal? = when (reason) {
            OutcomeSummary.Reason.IMPLICIT_DENY -> Refusal(
                "no statement permits an anonymous POST of it",
                "Permit http:POST on its path to anyone in a document.",
            )
            OutcomeSummary.Reason.EXPLICIT_DENY -> Refusal(
                "a DENY statement matches an anonymous POST of it",
                "Narrow the DENY the decision event names, so that it no longer matches the route.",
            )
            OutcomeSummary.Reason.BOUNDARY -> Refusal(
                "the tenant's platform boundary does not cover it",
                "Widen the boundary the platform document names for the tenant to cover the route.",
            )
            else -> null
        }
    }
}
