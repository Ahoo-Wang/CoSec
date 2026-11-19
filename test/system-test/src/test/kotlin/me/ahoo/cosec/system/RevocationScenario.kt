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

/**
 * Scenario 4, revocation and refresh across processes (IDN-15, IDN-16, IDN-18, IDN-26, MEM-4): tokens CoSec's login
 * issued, revoked through the login host, are refused by both gateways and the enforcing service, each of which had accepted
 * (and cached "not revoked" for) the token before. The processes share nothing but Redis and its invalidation messages.
 */
@Tag("system")
@Order(40)
@ExtendWith(TranscriptExtension::class)
class RevocationScenario {
    /** The same request, at both gateways and the enforcer, as [caller]: the token's app is the enforcer's. */
    private fun everywhere(caller: SignedIn) = Probe.everywhere("/orders/1", listOf(caller.bearer))

    private fun tokenRevoked(answer: Exchange): Boolean =
        answer.status == 401 && answer.header("Content-Type").orEmpty().startsWith("application/problem+json") &&
            answer.json()["code"] == "TOKEN_REVOKED"

    @Test
    fun `after a logout through the gateway the access token is refused at every process`() {
        val caller = SignIn.of("lou", "revocation", app = "orders")
        val probes = everywhere(caller)
        // Each process has accepted the token, and now holds a local "not revoked" answer for it.
        probes.forEach { it.send().status.assert().describedAs(it.name).isEqualTo(200) }

        SignIn.logout(caller, SystemTopology.gateway2).status.assert().isEqualTo(204)

        convergeEverywhere(Bounds.REVOCATION_PROPAGATION, "the logged-out token refused", probes, ::tokenRevoked)
        probes.forEach { Contract.assertProblem(it.send(), "token-revoked") }
        // The pair's refresh token is gone with its session.
        Contract.assertProblem(SignIn.refresh(caller), "token-revoked")
    }

    @Test
    fun `revoking a subject refuses every token it holds at every process, and a new sign-in works after the skew`() {
        val first = SignIn.of("rex", "revocation", app = "orders")
        val second = SignIn.of("rex", "revocation", app = "orders")
        val bystander = SignIn.of("bea", "revocation", app = "orders")
        listOf(first, second, bystander).forEach { caller ->
            everywhere(caller).forEach { it.send().status.assert().describedAs("${it.name} before").isEqualTo(200) }
        }

        Operator.revokeSubject("revocation", "rex")

        for (caller in listOf(first, second)) {
            convergeEverywhere(
                Bounds.REVOCATION_PROPAGATION,
                "rex's tokens refused",
                everywhere(caller),
                ::tokenRevoked
            )
        }
        // Another principal of the tenant, and the same name in another tenant, are not touched.
        everywhere(bystander).forEach { it.send().status.assert().describedAs("${it.name} bystander").isEqualTo(200) }
        everywhere(SignIn.of("rex", "revocation-other", app = "orders")).forEach {
            it.send().status.assert().describedAs("${it.name} same name, other tenant").isEqualTo(200)
        }
        // Tokens issued up to the skew after the revocation are refused too; one issued past it is accepted.
        eventually(
            Bounds.REVOCATION_SKEW,
            "a fresh sign-in of rex accepted",
            {
                Http.get(
                    SystemTopology.enforcer.resolve("/orders/1"),
                    SignIn.of("rex", "revocation", app = "orders").bearer
                )
            },
        ) { it.status == 200 }
    }

    @Test
    fun `a refresh rotates the pair, a pair presented again after its rotation ends the session (IDN-15, IDN-16)`() {
        val first = SignIn.of("rob", "revocation", app = "orders")
        val second = SignIn.pairOf(SignIn.refresh(first))
        second.accessToken.assert().isNotEqualTo(first.accessToken)
        second.claims["sid"].assert().isEqualTo(first.claims["sid"])
        (second.claims.getValue("cosec.generation") as Number).toLong()
            .assert().isEqualTo((first.claims.getValue("cosec.generation") as Number).toLong() + 1)
        // The rotated pair is in use at both gateways and at the enforcer.
        everywhere(second).forEach { it.send().status.assert().describedAs(it.name).isEqualTo(200) }

        // The pair just replaced is answered within the leeway, with a pair of the current generation, and moves nothing.
        SignIn.pairOf(SignIn.refresh(first, SystemTopology.gateway2))
        val third = SignIn.pairOf(SignIn.refresh(second))
        // Two generations back, the first pair is reuse: refused as revoked, and the session ends with it.
        Contract.assertProblem(SignIn.refresh(first), "token-revoked")
        Contract.assertProblem(SignIn.refresh(third), "token-revoked")
    }
}
