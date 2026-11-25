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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * `IDN-41` at the gate and along a chain of hops: a member a service does not know is ignored, and lost at the next hop
 * that rewrites the header, which is what makes it ignorable; a member `crit` names that the service does not know is
 * refused with `503` before anything acts, counted and logged apart from every other refusal: it names a reader older
 * than its writer counted on (RFC 0014 G3).
 */
class IdentityMustUnderstandTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(IdentityGate::class.java.name) as Logger

    @BeforeEach
    fun attach() {
        lines.start()
        logger.addAppender(lines)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(lines)
        logger.level = null
    }

    private val identity = Identity(
        tenant = "acme",
        subject = "alice",
        app = "",
        space = "",
        roles = setOf("clerk"),
        credential = Identity.Credential(
            Identity.Method.JWT,
            Identity.Credential.Kind.PERSON,
            "t1",
            now.plusSeconds(600)
        ),
        issuedAt = now,
        requestId = "r1",
        memberships = Identity.Memberships(emptyMap(), listOf("suspended")),
    )

    /** [identity]'s header with [extra] added to the root: what a writer of a later version writes. */
    private fun later(vararg extra: Pair<String, Any>): String {
        val json = String(Base64.getUrlDecoder().decode(IdentityCodec.encode(identity)))
        val root = CoSecJson.mapper.readTree(json) as ObjectNode
        extra.forEach { (name, value) -> root.set(name, CoSecJson.mapper.valueToTree(value)) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(CoSecJson.mapper.writeValueAsBytes(root))
    }

    private fun read(
        header: String,
        refusals: IdentityRefusals = IdentityRefusals(),
        deciding: Boolean = true,
    ): Outcome = runBlocking {
        val facts = HttpFacts(
            method = "GET",
            rawPath = "/orders",
            rawQuery = null,
            headers = Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
            cookies = Cookies.EMPTY,
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        IdentityGate(clock = clock, refusals = refusals, requireMemberships = deciding).evaluate(facts)
    }

    @Test
    fun `a member the service does not know is ignored, and the caller is the one the header states - IDN-41`() {
        val outcome = read(later("device" to "d1", "future" to mapOf("a" to listOf(1, 2))))

        outcome.proceed.assert().isTrue()
        outcome.security!!.identity.assert().isEqualTo(identity)
    }

    @Test
    fun `a member crit names that the service does not know is refused with 503, counted and logged apart - IDN-41`() {
        val refusals = IdentityRefusals()
        val outcome = read(later("device" to "d1", "crit" to listOf("device")), refusals)

        outcome.proceed.assert().isFalse()
        outcome.status.assert().isEqualTo(503)
        outcome.problem!!.type.assert().isEqualTo(ProblemType.UNAVAILABLE)
        refusals.count(ProblemType.UNAVAILABLE).assert().isEqualTo(1L)
        refusals.count(ProblemType.INVALID_REQUEST).assert().isEqualTo(0L)
        val line = lines.list.single { it.level == Level.WARN }.formattedMessage
        line.assert().contains("(503 unavailable)").contains("[device]").contains("upgrade this service")
            .doesNotContain("did not write")
    }

    @Test
    fun `a malformed crit is refused with 400, as any malformed header - IDN-41`() {
        val refusals = IdentityRefusals()
        read(later("crit" to "kind"), refusals).status.assert().isEqualTo(400)
        refusals.count(ProblemType.INVALID_REQUEST).assert().isEqualTo(1L)
        refusals.count(ProblemType.UNAVAILABLE).assert().isEqualTo(0L)
    }

    @Test
    fun `an ignorable member is lost at the next hop, and the host decides as without it - IDN-41`() {
        // A service that only trusts the gateway reads a later gateway's header and relays its caller.
        val admitted = read(later("device" to "d1"), deciding = false)
        admitted.proceed.assert().isTrue()
        val relayed = checkNotNull(IdentityIssuer(clock = clock, relay = true).issue(checkNotNull(admitted.security)))

        String(Base64.getUrlDecoder().decode(relayed)).assert().doesNotContain("\"device\"")
        val decided = read(relayed)
        decided.proceed.assert().isTrue()
        decided.security!!.memberships.attachments.map { it.value }.assert().containsExactly("suspended")
    }

    @Test
    fun `a must-understand member stops the chain at the first hop that does not know it - IDN-41`() {
        // The service refuses the header itself: there is no caller to relay, so nothing reaches the host without it.
        val refused = read(later("device" to "d1", "crit" to listOf("device")), deciding = false)

        refused.proceed.assert().isFalse()
        refused.status.assert().isEqualTo(503)
        refused.security.assert().isNull()
    }
}
