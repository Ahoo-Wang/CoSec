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
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.cocache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.login.route.RefreshRoute
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.PERSON
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.cosec.spring.boot.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.authentication.route.CoSecLoginRoutesAutoConfiguration
import me.ahoo.cosec.spring.boot.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.web.filter.HiddenHttpMethodFilter
import java.time.Instant

/**
 * `cosec.pipeline.caller=forwarded-identity` (`STA-9`): a host that decides with policies and believes the identity it is
 * sent, which is what a control host and an IAM service that embeds the control plane are. The control plane is wired
 * with no JWT and no signing secret; its web gate is the `IdentityGate`; it starts only under the operator's statement
 * of what can reach it; and what it decides for the principal the identity states, it decides by the documents.
 */
class ForwardedIdentityCallerTest {
    private val statement = "cosec.pipeline.identity.only-trusted-callers-can-reach"

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
            "cosec.authorization.snapshot.eviction-check=warn",
            "cosec.authorization.routes.location=",
            "cosec.pipeline.caller=forwarded-identity",
            "cosec.jwt.enabled=false",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    private fun failure(runner: ApplicationContextRunner): Throwable? {
        var failure: Throwable? = null
        runner.run { failure = it.startupFailure }
        return failure
    }

    private fun message(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `the control plane starts on the forwarded identity with no JWT and no secret, and the gate is the identity gate`() {
        runner.withPropertyValues("$statement=true").run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(RequestGate::class.java).assert().isInstanceOf(IdentityGate::class.java)
            // The data plane's pipeline is still built, and is not the gate.
            context.getBeansOfType(Pipeline::class.java).assert().hasSize(1)
            context.getBean(ControlPlane::class.java).assert().isNotNull()
            // No secret was asked for and no token is issued, but the revocations an approver is checked against are read.
            context.getBeansOfType(TokenStore::class.java).assert().hasSize(1)
        }
    }

    @Test
    fun `it does not start until the operator states what can reach it, and either statement will do`() {
        val refused = message(failure(runner))
        refused.assert().contains("$statement=true")
            .contains("cosec.pipeline.identity.only-gateway-can-reach=true")
            .contains("COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true")
            .contains("cosec.pipeline.caller=forwarded-identity")
            .contains("compromised workload")
            .contains("STA-9")
        for (key in listOf(statement, "cosec.pipeline.identity.only-gateway-can-reach")) {
            runner.withPropertyValues("$key=true").run { it.startupFailure.assert().isNull() }
        }
        for (value in listOf("false", "1", "on")) {
            message(failure(runner.withPropertyValues("$statement=$value"))).assert()
                .describedAs("with $statement=[$value]").contains("and $statement is [$value]")
        }
        // An empty value does not bind to a boolean: it fails startup too, and the failure names the key.
        message(failure(runner.withPropertyValues("$statement="))).assert().contains(statement)
    }

    @Test
    fun `a hidden-method filter is refused here as in gateway mode`() {
        val stated = runner.withPropertyValues("$statement=true")
        message(failure(stated.withPropertyValues("spring.webflux.hiddenmethod.filter.enabled=true")))
            .assert().contains("spring.webflux.hiddenmethod.filter.enabled=true").contains("STA-6")
        message(
            failure(stated.withBean("methodFilter", HiddenHttpMethodFilter::class.java, { HiddenHttpMethodFilter() }))
        ).assert().contains("a HiddenHttpMethodFilter bean, [methodFilter]")
    }

    @Test
    fun `a host that takes its callers from the credential is asked for none of it`() {
        runner.withPropertyValues("cosec.pipeline.caller=credential", "cosec.jwt.enabled=false")
            .withBean(
                me.ahoo.cosec.spi.token.RevocationReader::class.java,
                { me.ahoo.cosec.spi.token.RevocationReader.NONE }
            )
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(RequestGate::class.java).assert().isInstanceOf(Pipeline::class.java)
                context.getBeansOfType(IdentityGate::class.java).assert().isEmpty()
            }
    }

    @Test
    fun `the control plane decides for the principal the identity states, by the documents`() {
        runner.withPropertyValues("$statement=true").run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            val plane = context.getBean(ControlPlane::class.java)
            val plato = operator(context, "plato")
            runBlocking { plane.read(plato, SnapshotKey.Platform).assert().isNotNull() }
            // The same identity naming another principal is that principal: not bound, so refused.
            val mallory = operator(context, "mallory")
            runCatching { runBlocking { plane.read(mallory, SnapshotKey.Platform) } }.isFailure.assert().isTrue()
        }
    }

    /** The guard follows the gate: an application's own `IdentityGate`, declared `@Primary` beside `enforce`, is asked too. */
    @Test
    fun `an identity gate the application wires beside enforce is asked for the statement too`() {
        val web = WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecPipelineWebAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("cosec.jwt.enabled=false", "cosec.authorization.routes.location=")
            .withBean(
                "ownGate",
                IdentityGate::class.java,
                java.util.function.Supplier { IdentityGate() },
                BeanDefinitionCustomizer { it.isPrimary = true },
            )
        var refused: Throwable? = null
        web.run { refused = it.startupFailure }
        // What is true of this host: the gate is the application's bean, not a property the operator wrote.
        message(refused).assert().contains("STA-9").contains("$statement=true")
            .contains("the web gate of this host is an IdentityGate bean of the application, [ownGate]")
            .doesNotContain("cosec.pipeline.caller=forwarded-identity").doesNotContain("cosec.pipeline.mode=gateway")
        web.withPropertyValues("$statement=true").run { it.startupFailure.assert().isNull() }
    }

    /**
     * The embedded deployment: an IAM service that issues tokens and embeds the control plane. It verifies the
     * credentials of its own routes (the token authenticator is the data plane's), while its web gate believes the
     * identity it is sent.
     */
    @Test
    fun `an IAM service that issues tokens and embeds the control plane takes the forwarded caller beside its login`() {
        runner.withConfiguration(
            AutoConfigurations.of(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecLoginRoutesAutoConfiguration::class.java,
            ),
        ).withPropertyValues(
            "$statement=true",
            "cosec.jwt.enabled=true",
            "cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authentication.login.refresh.path=/auth/refresh",
            "cosec.authentication.login.logout.path=/auth/logout",
            "cosec.jwt.session.memberships-on-refresh=from-token",
        ).run { context ->
            context.startupFailure.assert().isNull()
            // The gate of the web adapter is the identity gate, and the pipeline that verifies tokens is not.
            context.getBean(RequestGate::class.java).assert().isInstanceOf(IdentityGate::class.java)
            context.getBean(Pipeline::class.java).assert().isNotSameAs(context.getBean(RequestGate::class.java))
            // The issuer and the login routes are wired, and the control plane is there too.
            context.getBean(TokenConverter::class.java).assert().isNotNull()
            context.getBean(RefreshRoute::class.java).assert().isNotNull()
            context.getBean(LogoutRoute::class.java).assert().isNotNull()
            context.getBean(Authenticator::class.java).assert().isNotNull()
            context.getBean(ControlPlane::class.java).assert().isNotNull()
        }
    }

    /** `CTL-57`: what the record keeps of a request the gate decided: it was asserted, by which credential, from which peer. */
    @Test
    fun `the operator of a request the identity gate decided is recorded as asserted, with the credential stated and the peer`() {
        runner.withPropertyValues("$statement=true").run { context ->
            val origin = operator(context, "plato", peer = "10.0.0.9").origin.assert().isNotNull().actual()
            origin.asserted.assert().isTrue()
            origin.via?.method.assert().isEqualTo("jwt")
            origin.via?.credentialId.assert().isEqualTo("jti-plato")
            origin.via?.requestId.assert().isNotNull()
            // The peer is the connection's, whatever the request states of its client address.
            origin.peer.assert().isEqualTo("10.0.0.9")
            operator(context, "plato").origin?.peer.assert().isNull()
        }
    }

    @Test
    fun `no header is no principal`() {
        runner.withPropertyValues("$statement=true").run { context ->
            val outcome = runBlocking { context.getBean(RequestGate::class.java).evaluate(facts(null)) }
            outcome.security?.principal.assert().isNull()
        }
    }

    private fun published(context: ApplicationContext) {
        val cache = context.getBean(PolicySnapshotCache::class.java)
        val documents = context.getBean(RedisSnapshotDocumentStore::class.java)
        cache.put(
            SnapshotKey.Platform,
            SnapshotDocuments.read(
                """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED_AT"$PLATFORM}""".encodeToByteArray()
            ),
        )
        documents.notifyChanged(SnapshotKey.Platform)
    }

    private fun facts(header: String?) = HttpFacts(
        method = "GET",
        rawPath = "/cosec/control/v1/documents/platform",
        rawQuery = null,
        headers = Headers.of(header?.let { mapOf(IdentityCodec.HEADER to listOf(it)) } ?: emptyMap()),
        cookies = Cookies.of(emptyMap()),
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )

    /**
     * DR2-C-1, `IDN-39`: the identity a CoSec gateway forwards carries the principal's attachments, and this host, which
     * decides on it, applies the DENY they reach; a header that does not state the memberships is refused here, never
     * read as a principal with none. The header is the gateway's own: its pipeline, its issuer.
     */
    @Test
    fun `a gateway's identity carries the attachments, and a header without memberships is refused - IDN-39`() {
        runner.withPropertyValues("$statement=true").run { context ->
            context.startupFailure.assert().isNull()
            published(context)
            val plane = context.getBean(ControlPlane::class.java)
            fun forwarded(policies: Set<String>): String {
                val now = Instant.now()
                val token = VerifiedToken(
                    "jti-plato",
                    "plato",
                    TenantId.PLATFORM.value,
                    "cosec-control",
                    PERSON,
                    emptySet(),
                    policies,
                    issuedAt = now,
                    expiresAt = now.plusSeconds(600),
                )
                val verifier = object : TokenVerifier {
                    override fun verify(accessToken: String): VerifiedToken = token
                    override fun refresh(token: CompositeToken): VerifiedToken = error("not refreshed")
                }
                return IdentityHeader.withToken("token", verifier, RevocationReader.NONE)
            }
            fun operatorOf(header: String): Operator? = runBlocking {
                context.getBean(RequestGate::class.java).evaluate(facts(header)).security?.let { Operator.of(it, null) }
            }
            val plain = checkNotNull(operatorOf(forwarded(emptySet())))
            runBlocking { plane.read(plain, SnapshotKey.Platform).assert().isNotNull() }
            val suspended = checkNotNull(operatorOf(forwarded(setOf("no-reading"))))
            runCatching { runBlocking { plane.read(suspended, SnapshotKey.Platform) } }.isFailure.assert().isTrue()

            val unstated = IdentityCodec.encode(
                IdentityCodec.decode(forwarded(setOf("no-reading"))).copy(memberships = null)
            )
            val refused = runBlocking { context.getBean(RequestGate::class.java).evaluate(facts(unstated)) }
            refused.status.assert().isEqualTo(400)
            refused.security?.principal.assert().isNull()
        }
    }

    /** The operator of a request that states [principal], as a gateway or a trusted service would. */
    private fun operator(context: ApplicationContext, principal: String, peer: String? = null): Operator {
        val now = Instant.now()
        val identity = Identity(
            tenant = TenantId.PLATFORM.value,
            subject = principal,
            app = "cosec-control",
            space = "",
            roles = emptySet(),
            credential = Identity.Credential(
                Identity.Method.JWT,
                PERSON,
                "jti-$principal",
                now.plusSeconds(600),
                issuedAt = now
            ),
            issuedAt = now,
            requestId = "req-$principal",
            memberships = Identity.Memberships.NONE,
        )
        val outcome = runBlocking {
            context.getBean(RequestGate::class.java).evaluate(facts(IdentityCodec.encode(identity)))
        }
        return Operator.of(outcome.security!!, peer)!!
    }

    private companion object {
        const val PUBLISHED_AT = "2026-10-28T00:00:00Z"
        const val PLATFORM = ""","policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]},""" +
            """{"id":"no-reading","attachable":true,"statements":[{"effect":"deny","action":"cosec:read"}]}],""" +
            """"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
            """{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"",""" +
            """"policy":"cosec:platform-admin"}]"""
    }
}
