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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit

/**
 * The two request readers hand the pipeline the same facts (round 2, R2B-25): `HttpFactsReader` on Reactor Netty,
 * Tomcat and Jetty's reactive servers, `ServletHttpFactsReader` on Tomcat and Jetty, each request sent as raw bytes.
 * `RealServerParityTest` compares the answers, which hide a difference in what was read unless a document reads that
 * value; this suite compares what was read: the method, the path and query as received, the client address, and every
 * value of the headers and cookies each request names, in order. The gateway reads with the WebFlux reader on Reactor
 * Netty, as the `netty` stack does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerFactsParityTest {
    private val fixture = ParityFixture()
    private val captures = STACKS.associateWith { Capture(fixture.pipeline) }
    private val servers = STACKS.associateWith {
        RealServer(
            it,
            captures.getValue(it),
            RealServerRemoteIp(ServletXForwardedRemoteIpResolver(1), ReactiveXForwardedRemoteIpResolver(1)),
        )
    }

    @AfterAll
    fun close() {
        servers.values.forEach { it.close() }
        fixture.close()
    }

    /**
     * A request, and the header and cookie names whose values every reader must read alike; [expected], what each must
     * read where servers differ in what they hand over (R2B-25, closed by R2B-8's readers).
     */
    class Scenario(
        val name: String,
        val request: String,
        val headers: List<String> = emptyList(),
        val cookies: List<String> = emptyList(),
        val expected: Map<String, Any?> = emptyMap(),
    ) {
        override fun toString(): String = name
    }

    fun scenarios(): List<Scenario> = listOf(
        Scenario("a plain request", get("/public/page"), headers = listOf("host", "x-absent")),
        Scenario(
            "a header repeated under two letter cases",
            get("/public/page", "X-Tag: one", "x-tag: two", "X-TAG: three"),
            headers = listOf("x-tag", "X-Tag"),
            // Reactor Netty's names are distinct by letter case; the reader asks the server for one name, ignoring case.
            expected = mapOf("header x-tag" to listOf("one", "two", "three")),
        ),
        Scenario(
            "a header of several values, on one line and on two",
            get("/public/page", "Accept: text/html, application/json", "Accept: */*"),
            headers = listOf("accept"),
        ),
        Scenario("a header with an empty value", get("/public/page", "X-Empty:"), headers = listOf("x-empty")),
        Scenario("a header with padding", get("/public/page", "X-Pad:   padded value  "), headers = listOf("x-pad")),
        Scenario("no Cookie header", get("/public/page"), headers = listOf("cookie"), cookies = listOf("a")),
        Scenario(
            "two cookies in one header",
            get("/public/page", "Cookie: a=1; b=2"),
            cookies = listOf("a", "b", "A"),
        ),
        Scenario("a cookie named twice", get("/public/page", "Cookie: a=1; a=2"), cookies = listOf("a")),
        Scenario(
            "two Cookie headers",
            get("/public/page", "Cookie: a=1", "Cookie: b=2; a=3"),
            cookies = listOf("a", "b"),
        ),
        Scenario(
            "a quoted cookie value",
            get("/public/page", "Cookie: q=\"quoted\""),
            cookies = listOf("q"),
            // Tomcat keeps the quotes, Jetty and Reactor Netty drop them: the readers read the value without them.
            expected = mapOf("cookie q" to listOf("quoted")),
        ),
        Scenario(
            "CoSec's authorization cookie",
            get("/public/page", "Cookie: authorization=t1; theme=dark"),
            cookies = listOf("authorization", "theme")
        ),
        // The reactive Tomcat and Jetty servers hand over no query for a bare `?`, the others an empty one: none, for all.
        Scenario("an empty query", get("/public/page?"), expected = mapOf("rawQuery" to null)),
        Scenario("a query", get("/public/page?x=1&y=%20&z")),
        Scenario("an encoded path", get("/public/a%20b/page")),
        Scenario("a PATCH request", raw("PATCH", "/public/page")),
        Scenario(
            "X-Forwarded-For on two lines",
            get("/public/page", "X-Forwarded-For: 203.0.113.7, 198.51.100.23", "X-Forwarded-For: 192.0.2.10"),
            headers = listOf("x-forwarded-for"),
        ),
    )

    @ParameterizedTest
    @MethodSource("scenarios")
    fun `every reader reads a request alike`(scenario: Scenario) {
        val read = STACKS.associateWith { stack ->
            val capture = captures.getValue(stack)
            capture.last = null
            capture.scenario = scenario
            val answer = servers.getValue(stack).send(scenario.request)
            checkNotNull(capture.last) { "$stack never reached the gate: $answer" }
        }
        val readings = read.entries.groupBy({ it.value }, { it.key })
        val described = readings.entries.joinToString("\n") { (facts, stacks) -> "$stacks read $facts" }
        readings.keys.assert().describedAs("${scenario.name}:\n$described").hasSize(1)
        val reading = readings.keys.single()
        for ((key, value) in scenario.expected) {
            reading.assert().describedAs("${scenario.name}: $key").containsEntry(key, value)
        }
    }

    /**
     * Why [Capture] reads while the request is decided, and why what outlives a request copies (`PIP-32`): once the
     * response is written, Tomcat recycles its request, and a view over it refuses to be read.
     */
    @Test
    fun `Tomcat refuses its request after the response`() {
        val capture = captures.getValue(Stack.TOMCAT)
        capture.scenario = scenarios().first()
        servers.getValue(Stack.TOMCAT).send(capture.scenario!!.request)
        val facts = checkNotNull(capture.facts)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECYCLE_BOUND_SECONDS)
        var refused: Throwable? = null
        while (refused == null && System.nanoTime() < deadline) {
            refused = runCatching { facts.headers.values("host") }.exceptionOrNull()
        }
        refused.assert().isInstanceOf(IllegalStateException::class.java)
    }

    private fun comparable(facts: HttpFacts, scenario: Scenario): Map<String, Any?> = buildMap {
        put("method", facts.method)
        put("rawPath", facts.rawPath)
        put("rawQuery", facts.rawQuery)
        put("remoteAddress", facts.remoteAddress)
        scenario.headers.forEach { put("header $it", facts.headers.values(it)) }
        scenario.cookies.forEach { put("cookie $it", facts.cookies.values(it)) }
    }

    /**
     * The pipeline, keeping what the last request it decided read for [scenario]: read while the request is decided,
     * since the readers are views over the server's request, which Tomcat recycles once the response is written.
     */
    private inner class Capture(private val pipeline: RequestGate) : RequestGate {
        @Volatile
        var scenario: Scenario? = null

        @Volatile
        var last: Map<String, Any?>? = null

        /** The facts of the last request, kept to show that they cannot be read after it. */
        @Volatile
        var facts: HttpFacts? = null

        override suspend fun evaluate(facts: HttpFacts): Outcome {
            this.facts = facts
            last = comparable(facts, checkNotNull(scenario))
            return pipeline.evaluate(facts)
        }
    }

    private companion object {
        val STACKS = Stack.entries - Stack.GATEWAY

        /** How long Tomcat may take to recycle a request whose response the client holds. */
        const val RECYCLE_BOUND_SECONDS = 5L

        fun get(path: String, vararg headers: String) = raw("GET", path, *headers)

        fun raw(method: String, path: String, vararg headers: String) =
            "$method $path HTTP/1.1\r\nHost: localhost\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"
    }
}
