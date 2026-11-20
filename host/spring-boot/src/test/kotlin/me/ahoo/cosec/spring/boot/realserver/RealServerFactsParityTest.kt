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
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

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
     * A request, and the header and cookie names whose values every reader must read alike. [split] is a difference
     * found and not yet decided: the groups of stacks that read alike within and differently across. It is pinned so a
     * change to either side is seen; the fix makes it one group and removes it.
     */
    class Scenario(
        val name: String,
        val request: String,
        val headers: List<String> = emptyList(),
        val cookies: List<String> = emptyList(),
        val split: Set<Set<Stack>>? = null,
    ) {
        override fun toString(): String = name
    }

    fun scenarios(): List<Scenario> = listOf(
        Scenario("a plain request", get("/public/page"), headers = listOf("host", "x-absent")),
        Scenario(
            "a header repeated under two letter cases",
            get("/public/page", "X-Tag: one", "x-tag: two", "X-TAG: three"),
            headers = listOf("x-tag", "X-Tag"),
            // Open (R2B-25): Netty's header names are distinct by letter case and each name's values are not, so the
            // WebFlux reader on Reactor Netty, and the gateway, read the three values three times each.
            split = setOf(setOf(Stack.NETTY), STACKS.toSet() - Stack.NETTY),
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
            // Open (R2B-25): Tomcat keeps the quotes as part of the value (RFC 6265 §4.1.1 allows them there); Jetty and
            // Netty unwrap them, and the gateway's credential stripping reads them as Netty does.
            split = setOf(
                setOf(Stack.TOMCAT, Stack.TOMCAT_REACTIVE),
                setOf(Stack.JETTY, Stack.NETTY, Stack.JETTY_REACTIVE)
            ),
        ),
        Scenario(
            "CoSec's authorization cookie",
            get("/public/page", "Cookie: authorization=t1; theme=dark"),
            cookies = listOf("authorization", "theme")
        ),
        Scenario(
            "an empty query",
            get("/public/page?"),
            // Open (R2B-25): the reactive Tomcat and Jetty servers' URI has no query for a bare `?`, so the WebFlux
            // reader reads none; the servlet containers and Reactor Netty read an empty one.
            split = setOf(
                setOf(Stack.TOMCAT_REACTIVE, Stack.JETTY_REACTIVE),
                setOf(Stack.TOMCAT, Stack.JETTY, Stack.NETTY)
            ),
        ),
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
            val answer = servers.getValue(stack).send(scenario.request)
            val facts = checkNotNull(capture.last) { "$stack never reached the gate: $answer" }
            comparable(facts, scenario)
        }
        val readings = read.entries.groupBy({ it.value }, { it.key })
        val described = readings.entries.joinToString("\n") { (facts, stacks) -> "$stacks read $facts" }
        readings.values.mapTo(HashSet()) { it.toSet() }.assert().describedAs("${scenario.name}:\n$described")
            .isEqualTo(scenario.split ?: setOf(STACKS.toSet()))
    }

    private fun comparable(facts: HttpFacts, scenario: Scenario): Map<String, Any?> = buildMap {
        put("method", facts.method)
        put("rawPath", facts.rawPath)
        put("rawQuery", facts.rawQuery)
        put("remoteAddress", facts.remoteAddress)
        scenario.headers.forEach { put("header $it", facts.headers.values(it)) }
        scenario.cookies.forEach { put("cookie $it", facts.cookies.values(it)) }
    }

    /** The pipeline, keeping the facts of the last request it decided. */
    private class Capture(private val pipeline: RequestGate) : RequestGate {
        @Volatile
        var last: HttpFacts? = null

        override suspend fun evaluate(facts: HttpFacts): Outcome {
            last = facts
            return pipeline.evaluate(facts)
        }
    }

    private companion object {
        val STACKS = Stack.entries - Stack.GATEWAY

        fun get(path: String, vararg headers: String) = raw("GET", path, *headers)

        fun raw(method: String, path: String, vararg headers: String) =
            "$method $path HTTP/1.1\r\nHost: localhost\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"
    }
}
