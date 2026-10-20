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

package me.ahoo.cosec.authorization

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.ExplainRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * `inRole` follows the principal's roles across spaces, so a deny keeps holding once tokens carry `role@space`.
 */
class InRoleSpaceTest {
    private fun globalPolicy(value: String): Policy = CoSecJsonSerializer.readValue(
        """
        {
          "id": "g", "name": "g", "category": "", "description": "", "type": "global", "tenantId": "(platform)",
          "statements": [
            { "name": "suspended", "effect": "deny", "action": "*", "condition": { "inRole": { "value": "$value" } } },
            { "name": "everyone", "effect": "allow", "action": "*" }
          ]
        }
        """.trimIndent(),
        Policy::class.java,
    )

    private fun decide(value: String, roles: Set<String>, spaceId: String): AuthorizeResult {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(globalPolicy(value)))
            every { getPolicies(any()) } returns Mono.just(listOf())
        }
        val permissions = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val request = ExplainRequest(path = "/orders", spaceId = spaceId).toRequest()
        val principal = SimplePrincipal("user", roles = roles)
        val result = SimpleAuthorization(policyRepository, permissions)
            .authorize(request, SimpleSecurityContext(principal)).block()!!
        // The explainer evaluates the same matchers and must reach the same decision.
        val explanation = AuthorizationExplainer(policyRepository, permissions).explain(request, principal).block()!!
        explanation.authorized.assert().isEqualTo(result == AuthorizeResult.ALLOW)
        return result
    }

    @Test
    fun denyKeepsMatchingAfterTokensSwitchToSpacedRoles() {
        decide("suspended", setOf("suspended"), "").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        decide("suspended", setOf("suspended@team-a"), "team-a").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
    }

    @Test
    fun spacedRoleDoesNotMatchInOtherSpaces() {
        decide("suspended", setOf("suspended@team-a"), "team-b").assert().isEqualTo(AuthorizeResult.ALLOW)
        decide("suspended", setOf("suspended@team-a"), "").assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun bareRoleMatchesInEverySpaceSoTheSpaceHeaderCannotEscapeADeny() {
        decide("suspended", setOf("suspended"), "team-a").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
    }

    @Test
    fun spacedValueIsComparedLiterally() {
        decide("suspended@team-a", setOf("suspended@team-a"), "").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        decide("suspended@team-a", setOf("suspended"), "team-a").assert().isEqualTo(AuthorizeResult.ALLOW)
        decide("suspended@team-a", setOf("suspended@team-b"), "team-a").assert().isEqualTo(AuthorizeResult.ALLOW)
    }
}
