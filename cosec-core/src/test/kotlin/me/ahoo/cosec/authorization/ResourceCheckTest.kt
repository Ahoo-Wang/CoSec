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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * The object-level pattern from the policies guide: the route is allowed in the request filter, where the resource
 * is not known yet, and the check in code, made with the loaded resource, denies whoever does not own it.
 */
class ResourceCheckTest {
    private val policy = CoSecJsonSerializer.readValue(
        """
        {
          "id": "orders",
          "name": "orders",
          "type": "global",
          "tenantId": "(platform)",
          "statements": [
            { "name": "use-orders", "action": "/orders/**", "condition": { "authenticated": {} } },
            {
              "name": "only-own-orders",
              "effect": "deny",
              "action": "/orders/**",
              "condition": { "bool": { "and": [
                { "eq": { "part": "resource.ownerId", "value": "", "negate": true } },
                { "eq": { "part": "resource.ownerId", "value": "#{principal.id}", "negate": true } }
              ] } }
            }
          ]
        }
        """.trimIndent(),
        Policy::class.java,
    )

    private val authorization = SimpleAuthorization(
        object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        },
        object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> = Mono.empty()
        },
    )
    private val checker = AuthorizationChecker(authorization)
    private val alice = SimpleSecurityContext(SimplePrincipal("alice"))

    @Test
    fun routeIsAllowedBeforeTheResourceIsKnown() {
        authorization.authorize(EvaluateRequest(path = "/orders/42", method = "PUT"), alice).block()
            .assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun checkWithResourceDeniesNonOwners() {
        val own = CheckRequest("/orders/42", "PUT", resource = mapOf("ownerId" to "alice"))
        val others = CheckRequest("/orders/43", "PUT", resource = mapOf("ownerId" to "bob"))
        checker.checkAll(alice, mapOf("own" to own, "others" to others)).block()
            .assert().isEqualTo(mapOf("own" to true, "others" to false))
    }
}
