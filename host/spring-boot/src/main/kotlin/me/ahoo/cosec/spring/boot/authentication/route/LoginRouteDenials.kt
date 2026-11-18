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
 * and its request is anonymous (`PIP-6`), so without a statement that permits an anonymous `POST` of it every refresh
 * is refused (`401 token-expired`) and every client signs out when its access token expires. Read from real traffic,
 * BEST_EFFORT, off the request path: a deny on the route (implicit or explicit; never a 429 or an indeterminate) logs
 * one WARN per route per [interval] naming the property and the path, and is kept for [LoginRoutesHealthIndicator]. A
 * later permit clears it. It never fails readiness: a document can change at any time, and it is the traffic that says.
 *
 * @param routes the property that sets each route, by the path it serves
 */
@InternalCoSecApi
class LoginRouteDenials(
    private val routes: Map<String, String>,
    private val interval: Duration = Duration.ofMinutes(1),
    private val clock: Clock = Clock.systemUTC(),
) : DecisionListener {
    override val name: String = "cosecLoginRouteDenials"

    override val delivery: Delivery = Delivery.BEST_EFFORT

    /** The routes refused at their last decision, by property, with the time of that decision. */
    private val denied = ConcurrentHashMap<String, Instant>()
    private val warned = ConcurrentHashMap<String, Instant>()

    override suspend fun onDecision(event: DecisionEvent) {
        if (event.request.method != POST) return
        val property = routes[event.request.path] ?: return
        when (event.outcome.reason) {
            OutcomeSummary.Reason.PERMIT -> denied.remove(property)
            OutcomeSummary.Reason.IMPLICIT_DENY, OutcomeSummary.Reason.EXPLICIT_DENY -> deny(
                property,
                event.request.path
            )
            else -> Unit
        }
    }

    private fun deny(property: String, path: String) {
        val now = clock.instant()
        denied[property] = now
        val last = warned[property]
        if (last != null && last.plus(interval).isAfter(now)) return
        if (warned.put(property, now) != last) return
        log.warn {
            "$property=$path: no statement permits an anonymous POST of it, so CoSec refuses it (401). Every refresh " +
                "and logout through it fails, and clients sign out when their access token expires. Permit " +
                "http:POST on http:path:$path to anyone in a document."
        }
    }

    /** The properties whose route was refused at its last decision, with when. */
    fun denied(): Map<String, Instant> = denied.toMap()

    private companion object {
        const val POST = "POST"
    }
}
