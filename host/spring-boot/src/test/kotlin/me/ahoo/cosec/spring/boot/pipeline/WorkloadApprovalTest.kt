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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * IDN-34, IDN-44 behind the gateway: a host that embeds the control plane and believes the identity it is forwarded
 * (`cosec.pipeline.caller=forwarded-identity`, as an IAM service of the embedded deployment runs), sent the header of
 * the kit's [IdentityHeader.forWorkload]. A workload never approves, whatever its principal holds; the same principal's
 * person's header approves.
 */
class WorkloadApprovalTest {
    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecGatewayModeAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            // The in-memory backend has no maxmemory-policy to read (SNP-3).
            "cosec.authorization.snapshot.eviction-check=warn",
            "cosec.authorization.routes.location=",
            "cosec.pipeline.caller=forwarded-identity",
            "cosec.pipeline.identity.only-gateway-can-reach=true",
            "cosec.jwt.enabled=false",
            // plato's own change needs a second person, so that an approval is what it waits for.
            "cosec.control.approval.four-eyes=true",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    private fun published(context: ApplicationContext) {
        val cache = context.getBean(PolicySnapshotCache::class.java)
        cache.put(
            SnapshotKey.Platform,
            SnapshotDocuments.read(
                """{"schema":2,"revision":1,"publishedAt":"2026-10-28T00:00:00Z"$PLATFORM}""".encodeToByteArray()
            ),
        )
        context.getBean(RedisSnapshotDocumentStore::class.java).notifyChanged(SnapshotKey.Platform)
    }

    /** The operator of a control API request that carries [header], as the gate makes it of the forwarded identity. */
    private fun operator(context: ApplicationContext, header: String): Operator {
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/cosec/control/v1/changes",
            rawQuery = null,
            headers = Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        val outcome = runBlocking { context.getBean(RequestGate::class.java).evaluate(facts) }
        return Operator.of(checkNotNull(outcome.security) { "the gate refused: ${outcome.status}" }, null)!!
    }

    private fun person(subject: String) =
        IdentityHeader.forSubject(subject, TenantId.PLATFORM.value, app = CONTROL_APP)

    @Test
    fun `a workload behind the gateway is refused an approval its principal's person would give - IDN-44`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            val plane = context.getBean(ControlPlane::class.java)
            val plato = operator(context, person("plato"))
            runBlocking {
                val policy = PolicyDefinition(
                    PolicyId("reads"),
                    false,
                    null,
                    listOf(StatementSource("", Effect.ALLOW, listOf("order:read")))
                )
                val change = plane.propose(plato, SnapshotKey.Platform, 1, listOf(Operation.PutPolicy(policy)), "reads")
                val waiting = plane.skipShadow(plato, change.id, "test")
                waiting.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)

                // dave holds cosec:platform-admin; as a workload, that approves nothing.
                val workload = operator(
                    context,
                    IdentityHeader.forWorkload("dave", TenantId.PLATFORM.value, app = CONTROL_APP)
                )
                val refused = assertThrows<ChangeRejectedException> {
                    runBlocking { plane.approve(workload, change.id, waiting.candidate!!) }
                }
                refused.status.assert().isEqualTo(FORBIDDEN)
                refused.message.assert().contains("an approval is a person's")
                plane.get(plato, change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)

                plane.approve(operator(context, person("dave")), change.id, waiting.candidate!!)
                    .state.assert().isEqualTo(ChangeState.READY)
            }
        }
    }

    private companion object {
        const val CONTROL_APP = "cosec-control"
        const val FORBIDDEN = 403
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
            """{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"",""" +
            """"policy":"cosec:platform-admin"},""" +
            """{"id":"dave","subject":{"principal":"dave","tenant":"(platform)"},"app":"cosec-control","space":"",""" +
            """"policy":"cosec:platform-admin"}]"""
    }
}
