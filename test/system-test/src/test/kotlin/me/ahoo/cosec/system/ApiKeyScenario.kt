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
import java.time.Instant

/**
 * Scenario 6, an API key at the enforcing service (IDN-1, IDN-2, PIP-8): a key issued into the shared Redis store by one
 * process is read by another; it grants only what its principal holds, narrowed by its cap; revoking it stops it on the
 * next request. The key is at the enforcer, not at a gateway: the gateway image has no `MembershipProvider`, so
 * `cosec.api-key.enabled=true` fails its startup (finding F2 of the system test's design, not decided yet).
 */
@Tag("system")
@Order(60)
@ExtendWith(TranscriptExtension::class)
class ApiKeyScenario {
    private val expiresAt: String get() = Instant.now().plusSeconds(3600).toString()

    private fun at(path: String, key: String): Exchange = Http.get(
        SystemTopology.enforcer.resolve(path),
        "X-API-Key" to key
    )

    @Test
    fun `a key holds what its principal holds narrowed by its cap, and stops once revoked`() {
        Control(SignIn.root()).seed("tenant:keys", "tenants/keys.json")
        // svc-reporter.extra holds the roles reporter and extra; the first key is capped to reporter, the second is not.
        val capped = Operator.issueKey("capped", "svc-reporter.extra", "keys", listOf("reporter"), expiresAt)
        val uncapped = Operator.issueKey("uncapped", "svc-reporter.extra", "keys", null, expiresAt)
        val nobody = Operator.issueKey("nobody", "stranger", "keys", null, expiresAt)

        eventually(
            Bounds.SNAPSHOT_PROPAGATION,
            "the capped key permitted",
            { at("/keys/reports/1", capped.key) }
        ) { it.status == 200 }
        val security = at("/keys/reports/1", capped.key).json()["security"] as Map<*, *>
        security["principal"].assert().isEqualTo("svc-reporter.extra")
        security["tenant"].assert().isEqualTo("keys")
        Contract.assertProblem(at("/keys/extra/1", capped.key), "forbidden")

        at("/keys/reports/1", uncapped.key).status.assert().isEqualTo(200)
        at("/keys/extra/1", uncapped.key).status.assert().isEqualTo(200)

        // A key whose principal the provider does not know is refused (IDN-3), as is a key nobody issued.
        Contract.assertProblem(at("/keys/reports/1", nobody.key), "token-invalid")
        Contract.assertProblem(at("/keys/reports/1", "not-a-key-0000000000000000"), "token-invalid")

        Operator.revokeKey(capped.hash)
        Contract.assertProblem(at("/keys/reports/1", capped.key), "token-invalid")
        at("/keys/reports/1", uncapped.key).status.assert().isEqualTo(200)
    }
}
