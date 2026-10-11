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
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class AuthorizationDecisionTest {

    @Test
    fun decideFallsBackToAuthorize() {
        val authorization = Authorization { _, _ -> AuthorizeResult.ALLOW.toMono() }
        authorization.decide(EvaluateRequest(), SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizationDecision(AuthorizeResult.ALLOW))
            .verifyComplete()
    }

    @Test
    fun authorizeDerivesFromDecide() {
        val authorization = object : DecisionAuthorization {
            override fun decide(request: me.ahoo.cosec.api.context.request.Request, context: SecurityContext) =
                AuthorizationDecision(AuthorizeResult.EXPLICIT_DENY, BlacklistVerifyContext).toMono()
        }
        authorization.authorize(EvaluateRequest(), SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun simpleAuthorizationReportsRootVerifyContext() {
        val authorization = SimpleAuthorization(mockk(), mockk())
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns CoSecPrincipal.ROOT_ID
        }
        authorization.decide(EvaluateRequest(), securityContext)
            .test()
            .expectNext(AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext))
            .verifyComplete()
    }

    @Test
    fun simpleAuthorizationReportsNoMatchVerifyContext() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, mockk())
        authorization.decide(EvaluateRequest(), SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY, NoMatchVerifyContext))
            .verifyComplete()
    }
}
