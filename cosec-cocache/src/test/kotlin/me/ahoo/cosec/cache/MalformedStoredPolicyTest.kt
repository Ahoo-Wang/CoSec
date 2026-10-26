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
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.MalformedPolicyException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

/**
 * 7.0.1 decoded `{"bool": {"and": "inRole"}}` as an empty `and` and stored the configuration as written. After the
 * upgrade such a stored policy fails its reads, and every request that needs it fails, rather than matching everything
 * or being skipped. The value stays in Redis until the policy is published again.
 */
class MalformedStoredPolicyTest {
    /** A condition as 7.0.1 kept it: the type and the configuration as written. */
    private val scalarAnd = object : ConditionMatcher {
        override val type: String = "bool"
        override val configuration: Configuration = mapOf("and" to "inRole").asConfiguration()
        override fun match(request: Request, securityContext: SecurityContext): Boolean = true
    }

    private val storedBy701 = PolicyData(
        id = "no-delete",
        category = "",
        name = "no-delete",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(
            StatementData(effect = Effect.DENY, action = AllActionMatcher.INSTANCE, condition = scalarAnd)
        ),
    )

    private val writer = CoSecTestCaches(CoSecTestCaches.redisBackend())
    private val reader = CoSecTestCaches(CoSecTestCaches.redisBackend(), writer.keyPrefix)

    @AfterEach
    fun destroy() {
        writer.close()
        reader.close()
    }

    @Test
    fun aStoredPolicyWithAScalarAndFailsEveryReadThatNeedsIt() {
        writer.policyCache.put(storedBy701.id, storedBy701)
        writer.globalPolicyIndexCache.add(CACHE_KEY, listOf(storedBy701.id))
        val authorization = SimpleAuthorization(reader.policyRepository(), mockk())
        val request = EvaluateRequest(path = "/orders/1", method = "DELETE")
        val context = SimpleSecurityContext(SimplePrincipal("user"))
        repeat(2) {
            authorization.decide(request, context)
                .test()
                .expectErrorMatches { error ->
                    generateSequence(error) { it.cause }.any {
                        it is MalformedPolicyException && it.path == "$.statements[0].condition.bool.and"
                    }
                }
                .verify()
        }
        // Publishing the fixed policy replaces the unreadable value without reading it.
        val fixed = PolicyData(
            id = storedBy701.id,
            category = "",
            name = storedBy701.name,
            description = "",
            type = PolicyType.GLOBAL,
            tenantId = "",
            statements = listOf(StatementData(effect = Effect.DENY, action = AllActionMatcher.INSTANCE)),
        )
        reader.policyRepository().setPolicy(fixed).block()
        authorization.decide(request, context).block()?.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
    }
}
