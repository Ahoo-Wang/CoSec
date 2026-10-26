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
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.LocalPolicyLoader
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.serialization.MalformedPolicyException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
import java.io.File

/**
 * A malformed policy never decides by being left out: wherever it is decoded, the decode error fails what needs the
 * policy. A malformed ALLOW does not allow, and a malformed DENY does not disappear and let another ALLOW through.
 */
class MalformedPolicyFailsClosedTest {
    private fun policyJson(id: String, type: String, statement: String): String =
        """{"id":"$id","name":"$id","type":"$type","tenantId":"t","statements":[$statement]}"""

    private val allowEverything = policyJson("allow", "global", """{"action":"*"}""")

    /** Meant for admins only; 7.0.1 read it as an ALLOW for everyone. */
    private val malformedAllow = policyJson(
        "admin",
        "global",
        """{"action":"*","conditon":{"inRole":{"value":"admin"}}}""",
    )

    /** Meant to deny the admin pages to non-admins; the scalar `and` is not a list. */
    private val malformedDeny = policyJson(
        "no-admin",
        "global",
        """{"effect":"deny","action":"/admin/**","condition":{"bool":{"and":"inRole","negate":true}}}""",
    )

    /**
     * Stores policies as JSON and decodes them on every read, as the Redis repository does.
     */
    private fun repository(global: List<String>, byId: Map<String, String> = emptyMap()): PolicyRepository = mockk {
        every { getGlobalPolicy() } returns Mono.fromCallable { global.map(::decode) }
        every { getPolicies(any()) } answers {
            val ids = firstArg<Set<String>>()
            Mono.fromCallable { ids.mapNotNull(byId::get).map(::decode) }
        }
    }

    private fun decode(json: String): Policy = CoSecJsonSerializer.readValue(json, Policy::class.java)

    private fun pipeline(repository: PolicyRepository, policies: Set<String> = emptySet()) = SecurityPipeline(
        object : RequestParser<Request> {
            override fun parse(request: Request): Request = request
        },
        SecurityContextParser { SimpleSecurityContext(SimplePrincipal("alice", policies = policies)) },
        SimpleAuthorization(
            repository,
            mockk { every { getAppRolePermission(any(), any(), any()) } returns Mono.empty() }
        ),
    )

    private fun SecurityPipeline<Request>.decide(path: String) =
        checkNotNull(evaluate(EvaluateRequest(path = path, method = "GET")).block())

    @Test
    fun aMalformedGlobalAllowFailsTheRequest() {
        val decision = pipeline(repository(listOf(malformedAllow))).decide("/orders")
        decision.status.assert().isEqualTo(SecurityStatus.ERROR)
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun aMalformedGlobalDenyFailsTheRequestInsteadOfDisappearing() {
        val pipeline = pipeline(repository(listOf(allowEverything, malformedDeny)))
        listOf("/admin/users", "/orders").forEach { path ->
            val decision = pipeline.decide(path)
            decision.status.assert().describedAs(path).isEqualTo(SecurityStatus.ERROR)
            decision.result.authorized.assert().describedAs(path).isFalse()
        }
    }

    @Test
    fun aMalformedPrincipalPolicyFailsTheRequest() {
        val repository =
            repository(listOf(allowEverything), mapOf("no-admin" to malformedDeny.replace("global", "custom")))
        pipeline(repository).decide("/admin/users").result.assert().isEqualTo(AuthorizeResult.ALLOW)
        val decision = pipeline(repository, setOf("no-admin")).decide("/admin/users")
        decision.status.assert().isEqualTo(SecurityStatus.ERROR)
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun aMalformedLocalPolicyFileStopsLoadingInsteadOfBeingSkipped(@TempDir dir: File) {
        File(dir, "allow-policy.json").writeText(allowEverything)
        File(dir, "no-admin-policy.json").writeText(malformedDeny)
        val error = assertThrows<IllegalStateException> {
            LocalPolicyLoader(setOf("file:${dir.absolutePath}/*-policy.json")).policies
        }
        error.message.assert().contains("no-admin-policy.json")
            .contains("Malformed JSON at $.statements[0].condition.bool.and")
        error.cause.assert().isInstanceOf(MalformedPolicyException::class.java)
    }
}
