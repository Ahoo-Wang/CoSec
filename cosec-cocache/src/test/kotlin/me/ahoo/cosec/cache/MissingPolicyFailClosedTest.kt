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

package me.ahoo.cosec.cache

import io.mockk.mockk
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * RFC 0001, D2: a principal references a DENY policy that the repository cannot find (deleted, not yet
 * replicated, lost from Redis). [RedisPolicyRepository.getPolicies] drops the id, so the deny disappears and a
 * broader global ALLOW wins: a suspended account regains access. Deny-first says missing data must not widen access.
 */
class MissingPolicyFailClosedTest {
    private val allowAll = PolicyData(
        id = "allow-all",
        category = "",
        name = "allow-all",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )

    // Attached to a principal to suspend the account.
    private val suspended = PolicyData(
        id = "suspended",
        category = "",
        name = "suspended",
        description = "",
        type = PolicyType.CUSTOM,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.DENY, action = AllActionMatcher.INSTANCE)),
    )

    private val caches = CoSecTestCaches().also {
        it.globalPolicyIndexCache.add(CACHE_KEY, listOf(allowAll.id))
        it.policyCache.put(allowAll.id, allowAll)
    }

    private fun authorization() = SimpleAuthorization(caches.policyRepository(), mockk())

    @AfterEach
    fun destroy() {
        caches.close()
    }

    private val request = EvaluateRequest(path = "/orders/1", method = "DELETE")
    private fun context() = SimpleSecurityContext(SimplePrincipal("user", policies = setOf(suspended.id)))

    @Test
    fun presentDenyPolicyDenies() {
        // Control: with the deny policy loaded, the request is denied.
        caches.policyCache.put(suspended.id, suspended)
        val decision = checkNotNull(authorization().decide(request, context()).block())
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun missingDenyPolicyDoesNotTurnIntoAllow() {
        val decision = authorization().decide(request, context()).onErrorResume { Mono.empty() }.block()
        // Either an error (mapped to 500 by the pipeline) or a deny is acceptable; an allow is not.
        (decision?.result?.authorized ?: false).assert().isFalse()
    }

    @Test
    fun missingPolicyFailsWithMissingPolicyException() {
        // Final semantics: SimpleAuthorization fails closed (the pipeline maps the error to 500).
        authorization().decide(request, context())
            .test()
            .expectError(MissingPolicyException::class.java)
            .verify()
    }
}
