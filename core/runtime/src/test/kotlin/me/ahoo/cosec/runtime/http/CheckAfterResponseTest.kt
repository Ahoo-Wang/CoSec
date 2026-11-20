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

package me.ahoo.cosec.runtime.http

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * `PIP-32`: a check reads the headers the request carried, never the server's request after the response. The host
 * here is a map a server could recycle: once [recycled], each lookup throws, as Tomcat's recycled request does, and
 * [lookups] counts what was asked of it after that.
 */
class CheckAfterResponseTest {
    private val pipeline = PipelineFixtures.pipeline(authenticator = authenticator(authenticated()))
    private val host = ConcurrentHashMap(mapOf("X-Team" to mutableListOf("red")))

    @Volatile
    private var recycled = false

    private val lookups = AtomicInteger()

    @Volatile
    private var inLookup: CountDownLatch? = null

    @Volatile
    private var release: CountDownLatch? = null

    private val team = Attribute.of(Attribute.Kind.HTTP_HEADER, "x-team")

    private fun lookup(name: String): List<String>? {
        if (recycled) {
            lookups.incrementAndGet()
            throw IllegalStateException("The request object has been recycled.")
        }
        inLookup?.countDown()
        release?.await(BOUND_SECONDS, TimeUnit.SECONDS)
        return host.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun permitted(): RequestSecurity {
        val facts = PipelineFixtures.facts("/orders/1").let {
            HttpFacts(
                it.method,
                it.rawPath,
                it.rawQuery,
                Headers.view(::lookup) { host.keys },
                it.cookies,
                it.remoteAddress,
                it.host,
            )
        }
        val outcome = runBlocking { pipeline.evaluate(facts) }
        outcome.proceed.assert().isTrue()
        return checkNotNull(outcome.security)
    }

    /** What the server does after the response: the adapter ends the request, then the server recycles it. */
    private fun respond(security: RequestSecurity) {
        security.end()
        recycled = true
        host.getValue("X-Team")[0] = "blue"
    }

    @Test
    fun `a check admitted before the end reads the copy after the server recycles the request`() {
        val security = permitted()
        security.admit().assert().isTrue()
        respond(security)
        security.attributes.string(team).assert().isEqualTo("red")
        lookups.get().assert().isZero()
    }

    @Test
    fun `an ended request admits no check and never goes back to the server`() {
        val security = permitted()
        respond(security)
        security.admit().assert().isFalse()
        security.engineRequest(0L).assert().isNull()
        lookups.get().assert().isZero()
    }

    @Test
    fun `the end waits for a check that is copying the headers`() {
        val security = permitted()
        inLookup = CountDownLatch(1)
        release = CountDownLatch(1)
        var admitted = false
        val check = thread { admitted = security.admit() }
        inLookup!!.await(BOUND_SECONDS, TimeUnit.SECONDS).assert().isTrue()
        val end = thread { respond(security) }
        // The adapter's end is held until the copy is made, so the server cannot recycle the request under it.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        while (end.state != Thread.State.BLOCKED && end.isAlive && System.nanoTime() < deadline) Thread.onSpinWait()
        end.state.assert().isEqualTo(Thread.State.BLOCKED)
        recycled.assert().isFalse()
        release!!.countDown()
        check.join()
        end.join()
        admitted.assert().isTrue()
        security.attributes.string(team).assert().isEqualTo("red")
        lookups.get().assert().isZero()
    }

    private companion object {
        /** How long the test waits for a thread to reach where it is expected; it fails past it. */
        const val BOUND_SECONDS = 5L
    }
}
