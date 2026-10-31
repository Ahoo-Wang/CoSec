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

package me.ahoo.cosec.spring.boot.starter.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import java.time.Instant

/**
 * RC1-13 through the starter: an approver whose memberships come from the membership provider (a token marked
 * `cosec.membership: provider`) is checked again at publish with the application's provider, so its approval counts.
 */
class ControlApprovalStarterTest {
    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            // The in-memory backend has no maxmemory-policy to read (SNP-3).
            "cosec.authorization.snapshot.eviction-check=warn",
            "${JwtProperties.PREFIX}.secret=$SECRET",
            "cosec.authorization.routes.location=",
            // plato's own change needs a second person, so that dave's approval is what publishes it.
            "cosec.control.approval.four-eyes=true",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withBean(MembershipLoader::class.java, {
            MembershipLoader { _, principal ->
                val roles = if (principal.value == "dave") setOf(RoleId("approver")) else emptySet()
                MembershipAnswer.Known(
                    Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to roles), emptyList())
                )
            }
        })

    /** The platform's documents: plato and the platform tenant's approvers administer, any principal is let in. */
    private fun published(context: ApplicationContext) {
        val cache = context.getBean(PolicySnapshotCache::class.java)
        val documents = context.getBean(RedisSnapshotDocumentStore::class.java)
        mapOf(
            SnapshotKey.Platform to PLATFORM,
            SnapshotKey.Tenant(TenantId.PLATFORM.value) to APPROVERS
        ).forEach { (key, body) ->
            cache.put(
                key,
                SnapshotDocument(
                    SnapshotDocument.DOCUMENT_SCHEMA,
                    1,
                    Instant.parse(PUBLISHED_AT),
                    """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED_AT"$body}""".encodeToByteArray(),
                ),
            )
            documents.notifyChanged(key)
        }
    }

    /** The operator of a control API request with [principal]'s token, its memberships from the provider or not. */
    private fun operator(context: ApplicationContext, principal: String, provider: Boolean): Operator {
        val plain: CoSecPrincipal = SimplePrincipal(principal)
        val marked = if (provider) MembershipMarkedPrincipal(plain, MembershipClaims.PROVIDER) else plain
        val token = context.getBean(TokenConverter::class.java)
            .toToken(SimpleTenantPrincipal(marked, SimpleTenant(TenantId.PLATFORM.value)))
            .accessToken
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/cosec/control/v1/changes",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf(token))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            val outcome = runBlocking { context.getBean(Pipeline::class.java).evaluate(facts) }
            outcome.security?.let { security -> return Operator.of(security)!! }
            check(System.nanoTime() < deadline) { "The host pipeline did not permit $principal: ${outcome.status}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `an approval by a provider-membership token publishes`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            val plato = operator(context, "plato", provider = false)
            val dave = operator(context, "dave", provider = true)
            val plane = context.getBean(ControlPlane::class.java)
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
                plane.approve(dave, change.id, waiting.candidate!!).state.assert().isEqualTo(ChangeState.READY)
                plane.publish(plato, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
            }
        }
    }

    /** RC1-11 through the starter (review item 6): an approver whose tokens are revoked since no longer counts. */
    @Test
    fun `an approval by a token revoked since does not publish`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            val plato = operator(context, "plato", provider = false)
            val dave = operator(context, "dave", provider = true)
            val plane = context.getBean(ControlPlane::class.java)
            runBlocking {
                val policy = PolicyDefinition(
                    PolicyId("writes"),
                    false,
                    null,
                    listOf(StatementSource("", Effect.ALLOW, listOf("order:write")))
                )
                val change = plane.propose(plato, SnapshotKey.Platform, 1, listOf(Operation.PutPolicy(policy)), "writes")
                val waiting = plane.skipShadow(plato, change.id, "test")
                plane.approve(dave, change.id, waiting.candidate!!).state.assert().isEqualTo(ChangeState.READY)
                context.getBean(TokenRevoker::class.java)
                    .revokeSubject(TenantId.PLATFORM, PrincipalId("dave"), Instant.now().plusSeconds(1))
                plane.publish(plato, change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
            }
        }
    }

    /** Review item 5: a revocation kept for less time than an approval counts would be forgotten while it does. */
    @Test
    fun `a revocation kept shorter than an approval counts fails startup`() {
        runner.withPropertyValues("cosec.control.approval.expire-after=8d").run { context ->
            val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString()
            message.assert().contains("cosec.jwt.token-validity.refresh").contains("cosec.control.approval.expire-after")
        }
    }

    /** Review item 6: without a revocation reader the control plane does not start, as the pipeline does not. */
    @Test
    fun `a control plane without a revocation reader fails startup`() {
        val untokened = runner.withPropertyValues("${JwtProperties.PREFIX}.enabled=false")
        untokened.run { context ->
            val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString()
            message.assert().contains("no revocation reader is available")
        }
        untokened.withBean(RevocationReader::class.java, { RevocationReader.NONE }).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(ControlPlane::class.java).assert().isNotNull()
        }
    }

    private companion object {
        const val SECRET = "FyN0Igd80Gas8stTavArGKOYnS9uLwGA_"
        const val PUBLISHED_AT = "2026-10-28T00:00:00Z"
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
            """{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"",""" +
            """"policy":"cosec:platform-admin"}]"""

        /** The platform tenant's approvers, by role: the provider says who they are. */
        const val APPROVERS = ""","bindings":[{"id":"approvers","subject":{"role":"approver"},"app":"cosec-control",""" +
            """"space":"","policy":"cosec:platform-admin"}]"""
    }
}
