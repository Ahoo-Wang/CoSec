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

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.AuthorizationChecker
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.kotlin.test.test

/**
 * The global policy index and the policy contents are separate caches, read one after the other. Whatever a reader
 * observes between or across the two publish writes, non-global content never applies to everyone, and an indexed
 * global policy whose content is missing denies instead of being skipped. Real CoCache caches, two instances sharing
 * one backend.
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class GlobalPolicyConsistencyTest(private val impl: AuthorizationImpl) {
    private val writer = CoSecTestCaches()
    private val reader = CoSecTestCaches(writer.runtime.backend, writer.keyPrefix)

    private fun policy(id: String, type: PolicyType, effect: Effect) = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = type,
        tenantId = "",
        statements = listOf(StatementData(effect = effect, action = AllActionMatcher.INSTANCE)),
    )

    private val request = EvaluateRequest(path = "/orders/1", method = "DELETE")
    private val anyone = SimpleSecurityContext(SimplePrincipal("user"))

    @AfterEach
    fun destroy() {
        reader.close()
        writer.close()
    }

    private fun authorization() =
        impl.create(reader.policyRepository(), reader.appRolePermissionRepository())

    private fun status(): SecurityStatus {
        val pipeline = SecurityPipeline(
            object : RequestParser<String> {
                override fun parse(request: String): Request = EvaluateRequest(path = request, method = "DELETE")
            },
            SecurityContextParser { anyone },
            authorization(),
        )
        return checkNotNull(pipeline.evaluate("/orders/1").block()).status
    }

    @Test
    fun interleavedWritersCannotMakeCustomContentGlobal() {
        val global = policy("p", PolicyType.GLOBAL, Effect.ALLOW)
        val custom = policy("p", PolicyType.CUSTOM, Effect.ALLOW)
        // Writer B republishes p as CUSTOM while writer A publishes it as GLOBAL:
        // B removes it from the index, A writes its content and adds it to the index, then B writes its content.
        writer.globalPolicyIndexCache.remove(CACHE_KEY, listOf("p"))
        writer.policyCache.put("p", global)
        writer.globalPolicyIndexCache.add(CACHE_KEY, listOf("p"))
        writer.policyCache.put("p", custom)

        reader.policyRepository().getGlobalPolicy().test().expectNext(emptyList()).verifyComplete()
        status().assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }

    @Test
    fun readerBetweenPublishWritesSeesOnlyGlobalContent() {
        val global = policy("p", PolicyType.GLOBAL, Effect.DENY)
        reader.policyRepository().setPolicy(global).block()
        reader.policyRepository().getGlobalPolicy().block().assert().isEqualTo(listOf(global))
        // The CUSTOM content lands while the index still lists the id (a failed index update, or another writer's
        // add): it applies only to principals that list it, never to everyone.
        writer.policyCache.put("p", policy("p", PolicyType.CUSTOM, Effect.ALLOW))

        reader.policyRepository().getGlobalPolicy().test().expectNext(emptyList()).verifyComplete()
        status().assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }

    @Test
    fun missingGlobalContentFailsClosed() {
        val allow = policy("allow", PolicyType.GLOBAL, Effect.ALLOW)
        writer.policyRepository().setPolicy(allow).block()
        // A global DENY whose content was lost: skipping it would let the ALLOW decide.
        writer.globalPolicyIndexCache.add(CACHE_KEY, listOf("lost-deny"))

        reader.policyRepository().getGlobalPolicy()
            .test()
            .expectErrorMatches { it is MissingPolicyException && it.policyIds == setOf("lost-deny") }
            .verify()
        status().assert().isEqualTo(SecurityStatus.ERROR)
        AuthorizationChecker(authorization()).check(anyone, "/orders/1", "DELETE")
            .test().expectNext(false).verifyComplete()
        AuthorizationExplainer(reader.policyRepository(), reader.appRolePermissionRepository())
            .explain(request, SimplePrincipal("user"))
            .test()
            .expectError(MissingPolicyException::class.java)
            .verify()
    }
}
