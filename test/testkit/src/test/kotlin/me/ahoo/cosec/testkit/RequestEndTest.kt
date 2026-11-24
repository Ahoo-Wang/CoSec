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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * UA-3, `PIP-26`: a test can end the request of the context the kit handed out, and see the check that code makes
 * after its response denied, as in production. UA-10: the fixture counts rate limits, so a test can see a 429.
 */
class RequestEndTest {
    private val everything = """{"policies":[{"id":"all","statements":[""" +
        """{"effect":"allow","action":"http:*","resource":"http:path:/orders/**"},""" +
        """{"effect":"allow","action":"order:read","resource":"order/{id}"}]}],""" +
        """"bindings":[{"id":"all","subject":"*","app":"*","space":"*","policy":"all"}]}"""

    private val alice = PipelineFixture.Principal("alice", tenant = "acme")

    @Test
    fun `a fixture context ended is denied`() = runBlocking<Unit> {
        PipelineFixture.of(platform = everything).use { fixture ->
            val checker = AuthorizationChecker(fixture.pipeline)
            val context = fixture.securityContext(fixture.request("GET", "/orders/1", alice))
            checker.check(context, "order:read", "order/42").assert().isTrue()
            fixture.end(context)
            fixture.end(context)
            checker.decide(context, "order:read", "order/42").assert().isEqualTo(Decision.Deny(null))
            // An outcome of evaluate ends its request when it is closed, as a transport closes it.
            val outcome = fixture.evaluate(fixture.request("GET", "/orders/1", alice))
            val kept = outcome.use { it.security!!.securityContext }
            checker.check(kept, "order:read", "order/42").assert().isFalse()
            assertThrownBy<IllegalArgumentException> { fixture.end(mockk<SecurityContext>()) }
        }
    }

    @Test
    fun `a permitted request ended is denied`() = runBlocking<Unit> {
        val ids = AtomicLong()
        val algorithm = JwtSigning.hmac256("request-end-test-secret")
        val token = JwtTokenConverter(IdGenerator { "t-${ids.incrementAndGet()}" }, algorithm)
            .toToken(TokenSubject("alice", Identity.Credential.Kind.PERSON, tenant = "acme")).accessToken
        val context = PermittedRequest.withToken(token, JwtTokenVerifier(algorithm), RevocationReader.NONE)
        PipelineFixture.of(platform = everything).use { fixture ->
            val checker = AuthorizationChecker(fixture.pipeline)
            checker.check(context, "order:read", "order/42").assert().isTrue()
            PermittedRequest.end(context)
            checker.decide(context, "order:read", "order/42").assert().isEqualTo(Decision.Deny(null))
        }
        assertThrownBy<IllegalArgumentException> { PermittedRequest.end(mockk<SecurityContext>()) }
    }

    @Test
    fun `a request over a rate limit is 429`() = runBlocking<Unit> {
        val limited = everything.dropLast(1) + ""","rateLimits":[{"id":"one","action":"*","permitsPerSecond":1}]}"""
        // The kit's default limiter on the real clock (UA-10: it counts with no limiter passed). Its window slides: a
        // request just past a window boundary is permitted again, so "the second request is 429" depends on when the
        // test runs. What holds whatever the clock does: in a bounded run of back-to-back requests one is refused, after
        // a permit. Two permits in a row need a window boundary between them, so twenty never refused would take about
        // twenty seconds of stalls.
        PipelineFixture.of(platform = limited).use { fixture ->
            val request = fixture.request("GET", "/orders/1", alice)
            val statuses = mutableListOf<Int>()
            while (statuses.size < BOUND && 429 !in statuses) statuses += fixture.evaluate(request).status
            statuses.assert().describedAs("one request refused within $BOUND").contains(429)
            statuses.dropLast(1).assert().isNotEmpty().containsOnly(200)
        }
        var now = 0L
        PipelineFixture.of(platform = limited, rateLimiter = LocalRateLimiter(clock = { now })).use { fixture ->
            val request = fixture.request("GET", "/orders/1", alice)
            fixture.evaluate(request).status.assert().isEqualTo(200)
            fixture.evaluate(request).status.assert().isEqualTo(429)
            now += 2_000
            fixture.evaluate(request).status.assert().isEqualTo(200)
        }
    }

    private companion object {
        /** Back-to-back requests within which a rule of one permit a second refuses one, whatever the clock. */
        const val BOUND = 20
    }
}
