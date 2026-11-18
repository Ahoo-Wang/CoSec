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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * What the operator is told when a document refuses a served login route (`IDN-23`): which refusal it was, once per
 * route per interval, on the health indicator until a permit clears it, and nothing for what the documents did not decide.
 */
@ExtendWith(OutputCaptureExtension::class)
class LoginRouteDenialsTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovingClock(Instant.parse("2026-11-18T00:00:00Z"))
    private val routes = mapOf(REFRESH to PROPERTY)
    private val denials = LoginRouteDenials(routes, Duration.ofMinutes(1), clock)
    private val health = LoginRoutesHealthIndicator(routes, denials)

    private fun detail(): Any? = health.health().details[PROPERTY]

    @Test
    fun `an implicit deny says that no statement permits the route`(output: CapturedOutput) {
        denials.record("POST", REFRESH, Reason.IMPLICIT_DENY)
        output.all.assert().contains("$PROPERTY=$REFRESH: no statement permits an anonymous POST of it")
            .contains("Permit http:POST on its path to anyone")
        detail().assert().isEqualTo("denied since ${clock.now}: no statement permits an anonymous POST of it")
    }

    @Test
    fun `an explicit deny says that a DENY statement matches it`(output: CapturedOutput) {
        denials.record("POST", REFRESH, Reason.EXPLICIT_DENY)
        output.all.assert().contains("$PROPERTY=$REFRESH: a DENY statement matches an anonymous POST of it")
            .doesNotContain("no statement permits")
        detail().assert().isEqualTo("denied since ${clock.now}: a DENY statement matches an anonymous POST of it")
    }

    @Test
    fun `a boundary says that the tenant's platform boundary does not cover it`(output: CapturedOutput) {
        denials.record("POST", REFRESH, Reason.BOUNDARY)
        output.all.assert().contains("$PROPERTY=$REFRESH: the tenant's platform boundary does not cover it")
        detail().assert().isEqualTo("denied since ${clock.now}: the tenant's platform boundary does not cover it")
    }

    @ParameterizedTest
    @EnumSource(
        Reason::class,
        names = [
            "TOO_MANY_REQUESTS", "SCOPE_REJECTED", "INDETERMINATE", "AUTHENTICATOR_UNAVAILABLE",
            "DEADLINE_EXCEEDED", "INVALID_REQUEST", "CANCELLED", "ERROR"
        ],
    )
    fun `what the documents did not decide is not a refusal of the route`(reason: Reason, output: CapturedOutput) {
        denials.record("POST", REFRESH, reason)
        denials.denied().assert().isEmpty()
        output.all.assert().doesNotContain(PROPERTY)
        detail().assert().isEqualTo("not refused")
    }

    @Test
    fun `one warning per route per interval, and a permit clears the route`(output: CapturedOutput) {
        denials.record("POST", REFRESH, Reason.IMPLICIT_DENY)
        denials.record("POST", REFRESH, Reason.IMPLICIT_DENY)
        clock.now = clock.now.plusSeconds(30)
        denials.record("POST", REFRESH, Reason.IMPLICIT_DENY)
        Regex("no statement permits an anonymous POST").findAll(output.all).count().assert().isEqualTo(1)
        clock.now = clock.now.plusSeconds(31)
        denials.record("POST", REFRESH, Reason.IMPLICIT_DENY)
        Regex("no statement permits an anonymous POST").findAll(output.all).count().assert().isEqualTo(2)
        denials.record("POST", REFRESH, Reason.PERMIT)
        detail().assert().isEqualTo("not refused")
        health.health().status.assert().isEqualTo(Status.UP)
    }

    @Test
    fun `another method or another path is not the route`() {
        denials.record("GET", REFRESH, Reason.IMPLICIT_DENY)
        denials.record("POST", "/orders", Reason.IMPLICIT_DENY)
        denials.denied().assert().isEmpty()
        health.health().status.assert().isEqualTo(Status.UP)
    }

    private companion object {
        const val REFRESH = "/auth/refresh"
        const val PROPERTY = "cosec.authentication.login.refresh.path"
    }
}
