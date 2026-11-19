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

package me.ahoo.cosec.system

import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Requests that must be refused, sent to every gateway without a pause on a thread of their own: a DENY, an anonymous
 * caller and a token of another secret. A 2xx answer to one of them is [wrong], whatever the cause, and goes into the
 * transcript with its response; a connection error is not (a replica that is not listening permitted nothing). One
 * permitted request to [permitted]'s gateway counts what is still served.
 */
class RefusedTraffic(private val gateways: List<URI>, private val permitted: URI, signedIn: Token) {
    private class MustDeny(val path: String, val headers: List<Pair<String, String>>)

    private val foreign = Token.issue("rene", "restart", secret = "not-the-gateways-secret-0123456789abcdef")
    private val mustDeny = listOf(
        MustDeny("/behind/denied/1", listOf(signedIn.bearer)),
        MustDeny("/behind/orders/1", emptyList()),
        MustDeny("/behind/orders/1", listOf(foreign.bearer)),
    )
    private val permit = listOf(signedIn.bearer)
    private val running = AtomicBoolean(true)
    private val worker = thread(name = "system-traffic") { loop() }

    /** The refusals answered so far. */
    val refusals = AtomicInteger()

    /** The permitted requests answered `200` so far. */
    val permits = AtomicInteger()

    /** Every answer that must not have been a 2xx and was. */
    val wrong = CopyOnWriteArrayList<String>()

    private fun loop() {
        while (running.get()) {
            for (gateway in gateways) {
                mustDeny.forEach { refuse(gateway, it) }
            }
            if (answer(permitted.resolve("/behind/orders/1"), permit) { false }?.status == 200) {
                permits.incrementAndGet()
            }
        }
    }

    private fun refuse(gateway: URI, request: MustDeny) {
        val answer = answer(gateway.resolve(request.path), request.headers) { it.status in 200..299 } ?: return
        if (answer.status in 200..299) wrong += "$gateway ${request.path} -> $answer" else refusals.incrementAndGet()
    }

    private fun answer(uri: URI, headers: List<Pair<String, String>>, keep: (Exchange) -> Boolean): Exchange? = try {
        Http.getQuietly(uri, headers, keep)
    } catch (_: IOException) {
        null
    }

    fun stop() {
        running.set(false)
        worker.join()
    }
}
