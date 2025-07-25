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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyEvaluator
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import java.net.URI

object DefaultPolicyEvaluator : PolicyEvaluator {
    override fun evaluate(policy: Policy) {
        val evaluateRequest = EvaluateRequest()
        val mockContext = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS)
        safeEvaluate {
            policy.condition.match(request = evaluateRequest, securityContext = mockContext)
        }
        policy.statements.forEach { statement ->
            safeEvaluate {
                statement.condition.match(request = evaluateRequest, securityContext = mockContext)
            }
            statement.action.match(request = evaluateRequest, securityContext = mockContext)
            safeEvaluate {
                statement.verify(request = evaluateRequest, securityContext = mockContext)
            }
        }

        safeEvaluate {
            policy.verify(request = evaluateRequest, securityContext = mockContext)
        }
    }

    internal fun safeEvaluate(verifyFun: () -> Unit) {
        try {
            verifyFun()
        } catch (ignore: TooManyRequestsException) {
            // ignore
        }
    }
}

data class EvaluateRequest(
    override val path: String = "/policies/test",
    override val method: String = "POST",
    override val remoteIp: String = "127.0.0.1",
    override val origin: URI = URI.create("http://mockOrigin"),
    override val referer: URI = URI.create("http://mockReferer"),
    override val attributes: Map<String, String> = mapOf(),
    private val headers: Map<String, String> = mapOf(),
    private val queries: Map<String, String> = mapOf(),
    private val cookies: Map<String, String> = mapOf(),
) : Request {

    override fun getHeader(key: String): String {
        return headers[key].orEmpty()
    }

    override fun getQuery(key: String): String {
        return queries[key].orEmpty()
    }

    override fun getCookieValue(key: String): String {
        return cookies[key].orEmpty()
    }

    override fun withAttributes(attributes: Map<String, String>): Request {
        return copy(attributes = attributes)
    }
}
