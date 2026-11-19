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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import java.time.Instant

/**
 * Scenario 9, a token from an issuer whose clock runs ahead (#1666, IDN-29): the verifier of every process tolerates an
 * issuer's clock ahead by `cosec.authentication.revocation.skew` (5 s by default), so a token whose `iat` is a couple of
 * seconds past the verifier's clock is accepted at both gateways and the enforcer; one stamped well beyond the skew is
 * `401 token-invalid`; and an expired token stays refused whatever the skew: the tolerance is for the issuer's clock, it
 * never lengthens a token's life. The tokens are signed by the harness, a process of its own with its own clock, as another
 * service issuing for this deployment would.
 */
@Tag("system")
@Order(45)
@ExtendWith(TranscriptExtension::class)
class ClockSkewScenario {
    private fun at(issuedAt: Instant, validity: Duration = Duration.ofMinutes(10)): List<Probe> = Probe.everywhere(
        "/orders/1",
        listOf(Token.issue("sam", "skew", app = "orders", issuedAt = issuedAt, validity = validity).bearer),
    )

    @Test
    fun `a token stamped a couple of seconds ahead is accepted at every process`() {
        at(Instant.now().plusSeconds(2)).forEach { it.send().status.assert().describedAs(it.name).isEqualTo(200) }
    }

    @Test
    fun `a token stamped well beyond the skew is invalid at every process`() {
        at(Instant.now().plusSeconds(60)).forEach { Contract.assertProblem(it.send(), "token-invalid") }
    }

    @Test
    fun `an expired token stays refused, even one that expired within the skew`() {
        // iat 10 s ago, valid for 8 s: expired 2 s ago, inside a 5 s skew.
        at(
            Instant.now().minusSeconds(10),
            Duration.ofSeconds(8)
        ).forEach { Contract.assertProblem(it.send(), "token-expired") }
    }
}
