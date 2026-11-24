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

package me.ahoo.cosec.runtime.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.captured
import me.ahoo.cosec.runtime.forget
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * #1690, `OBS-7`: a refused forwarded identity is counted by problem type, every time, and says why in the log without
 * DEBUG, at most once a minute per problem type, and never in the response.
 */
class IdentityRefusalsTest {
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(IdentityGate::class.java.name) as Logger
    private var level: Level? = null

    @BeforeEach
    fun attach() {
        level = logger.level
        logger.level = Level.DEBUG
        lines.start()
        logger.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(lines)
        logger.level = level
    }

    private fun warnings() = lines.captured().filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun refused(gate: IdentityGate, header: String) = runBlocking {
        gate.evaluate(
            HttpFacts(
                "GET",
                "/orders",
                null,
                Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
                Cookies.EMPTY,
                "10.0.0.1",
                HostScope(null),
            )
        )
    }

    @Test
    fun `the gate warns once for a flood of bad headers, and the response says only the problem`() {
        val gate = IdentityGate()
        val outcomes = List(3) { refused(gate, "not base64!") }
        outcomes.forEach { it.status.assert().isEqualTo(ProblemType.INVALID_REQUEST.status) }
        val body = String(ProblemEncoder.encode(outcomes.first().problem!!))
        body.assert().doesNotContain("base64")
        val warned = warnings()
        warned.assert().hasSize(1)
        val warning = warned.single()
        warning.assert().contains("400 invalid-request").contains("not base64url").contains(IdentityRefusals.PAGE)
        lines.captured().filter { it.level == Level.DEBUG }.assert().hasSize(2)
    }

    @Test
    fun `each problem type warns again once its interval has passed`() {
        var now = 0L
        val refusals = IdentityRefusals(Duration.ofMinutes(1)) { now }
        refusals.record("r1", ProblemType.TOKEN_EXPIRED, "the identity was issued long ago")
        refusals.record("r2", ProblemType.TOKEN_INVALID, "the identity is from the future")
        now += Duration.ofSeconds(59).toNanos()
        refusals.record("r3", ProblemType.TOKEN_EXPIRED, "again")
        now += Duration.ofSeconds(1).toNanos()
        refusals.record("r4", ProblemType.TOKEN_EXPIRED, "again")
        warnings().map { it.substringBefore(']') }.assert().containsExactly("Request [r1", "Request [r2", "Request [r4")
    }

    /**
     * `OBS-7`: the logged reason carries nothing of the header unescaped. A caller around the gateway forges a claim name,
     * a member name or a token that is not JSON with a line break and control characters, 5,000 characters long: each
     * refusal is one WARN line of bounded length with no control character, the forged text escaped, never written raw.
     */
    @Test
    fun `a forged header writes one bounded warning line, with nothing of the header unescaped`() {
        val now = Instant.now().epochSecond
        val forged = "x\\n2026-11-19 00:00:00 ERROR forged\\u0007\\r" + "y".repeat(5_000)
        val prefix = """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""},"roles":["clerk"],""" +
            """"auth":{"method":"jwt","jti":"t1","exp":${now + 3600}},"iat":$now,"rid":"r1""""
        val headers = listOf(
            "$prefix,\"claims\":{\"$forged\":{}}}",
            // A member it does not know is ignored (IDN-41); a forged entry of `crit` is refused unquoted.
            "$prefix,\"crit\":[\"$forged\"]}",
            "{\"v\":${"a".repeat(5_000)}\nERROR forged}",
        ).map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) }
        headers.forEach { header ->
            lines.forget()
            refused(IdentityGate(maxBytes = 64 * 1024), header).status.assert().isEqualTo(400)
            val warning = warnings().single()
            warning.any { it.isISOControl() }.assert().isFalse()
            (warning.length < 1_000).assert().isTrue()
            warning.assert().doesNotContain("y".repeat(100)).doesNotContain("a".repeat(100))
        }
    }

    @Test
    fun `every refusal is counted by problem type, also those the bounded warning does not print`() {
        val refusals = IdentityRefusals()
        val gate = IdentityGate(refusals = refusals)
        repeat(3) { refused(gate, "not base64!") }
        warnings().assert().hasSize(1)
        refusals.count(ProblemType.INVALID_REQUEST).assert().isEqualTo(3L)
        refusals.count(ProblemType.TOKEN_INVALID).assert().isEqualTo(0L)
        refusals.count(ProblemType.TOKEN_EXPIRED).assert().isEqualTo(0L)
    }

    @Test
    fun `a request the gate lets through counts nothing, and no other problem is counted`() {
        val refusals = IdentityRefusals()
        val gate = IdentityGate(refusals = refusals)
        val anonymous = runBlocking {
            gate.evaluate(HttpFacts("GET", "/orders", null, Headers.EMPTY, Cookies.EMPTY, "10.0.0.1", HostScope(null)))
        }
        anonymous.proceed.assert().isTrue()
        IdentityRefusals.TYPES.forEach { refusals.count(it).assert().isEqualTo(0L) }
        refusals.count(ProblemType.FORBIDDEN).assert().isEqualTo(0L)
    }
}
