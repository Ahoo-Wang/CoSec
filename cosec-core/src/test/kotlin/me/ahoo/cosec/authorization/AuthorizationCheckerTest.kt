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

import io.mockk.mockk
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.cosec.policy.isPolicyEvaluation
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class AuthorizationCheckerTest(private val impl: AuthorizationImpl) {
    private val seen = CopyOnWriteArrayList<Pair<Request, SecurityContext>>()

    /** Allows GET under /orders and anything for app "admin"; records what it was asked. */
    private val authorization = Authorization { request, context ->
        seen += request to context
        val allowed = (request.method == "GET" && request.path.startsWith("/orders")) || request.appId == "admin"
        (if (allowed) AuthorizeResult.ALLOW else AuthorizeResult.IMPLICIT_DENY).toMono()
    }

    private val context = SimpleSecurityContext(SimplePrincipal("user"))
    private val checker = AuthorizationChecker(authorization)

    @Test
    fun check() {
        checker.check(context, "/orders/1").test().expectNext(true).verifyComplete()
        checker.check(context, "/orders/1", "DELETE").test().expectNext(false).verifyComplete()
        checker.check(context, CheckRequest("/users", appId = "admin")).test().expectNext(true).verifyComplete()
    }

    @Test
    fun checkAll() {
        val requests = mapOf(
            "view" to CheckRequest("/orders/1"),
            "delete" to CheckRequest("/orders/1", "DELETE"),
        )
        checker.checkAll(context, requests)
            .test()
            .consumeNextWith { it.assert().isEqualTo(mapOf("view" to true, "delete" to false)) }
            .verifyComplete()
    }

    @Test
    fun runsOnIsolatedPolicyEvaluationContext() {
        checker.check(context, "/orders/1").block()
        val checked = seen.single().second
        checked.assert().isNotSameAs(context)
        checked.principal.id.assert().isEqualTo("user")
        checked.isPolicyEvaluation.assert().isTrue()
        context.isPolicyEvaluation.assert().isFalse()
    }

    @Test
    fun inheritsAppSpaceAndIpFromCurrentRequest() {
        val current = EvaluateRequest(
            remoteIp = "10.0.0.9",
            headers = mapOf(AppIdCapable.APP_ID_KEY to "admin", "CoSec-Space-Id" to "s1"),
        )
        context.setRequest(current)
        checker.check(context, "/users", "DELETE").test().expectNext(true).verifyComplete()
        val checked = seen.single().first
        checked.appId.assert().isEqualTo("admin")
        checked.spaceId.assert().isEqualTo("s1")
        checked.remoteIp.assert().isEqualTo("10.0.0.9")
        // An explicit app wins over the current one.
        checker.check(
            context,
            CheckRequest("/users", "DELETE", appId = "shop")
        ).test().expectNext(false).verifyComplete()
    }

    @Test
    fun errorsDeny() {
        val failing = AuthorizationChecker(Authorization { _, _ -> Mono.error(IllegalStateException("boom")) })
        failing.check(context, "/orders/1").test().expectNext(false).verifyComplete()
        val throwing = AuthorizationChecker(Authorization { _, _ -> throw IllegalStateException("boom") })
        throwing.check(context, "/orders/1").test().expectNext(false).verifyComplete()
        val empty = AuthorizationChecker(Authorization { _, _ -> Mono.empty() })
        empty.check(context, "/orders/1").test().expectNext(false).verifyComplete()
    }

    @Test
    fun checksWithTheImplementationWithoutLeakingPathVariables() {
        val policy = CoSecJsonSerializer.readValue(
            """
            {
              "id": "own-orders", "name": "own-orders", "type": "global", "tenantId": "(platform)",
              "statements": [
                {
                  "name": "own", "action": "/users/{userId}/orders/**",
                  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } }
                }
              ]
            }
            """.trimIndent(),
            Policy::class.java,
        )
        val policyRepository = object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val implementation = AuthorizationChecker(impl.create(policyRepository, mockk()))
        implementation.check(context, "/users/user/orders/1").test().expectNext(true).verifyComplete()
        implementation.check(context, "/users/other/orders/1").test().expectNext(false).verifyComplete()
        context.getPathVariables().assert().isNull()
    }
}
