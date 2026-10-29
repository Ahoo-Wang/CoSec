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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.claims
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.context.SimpleSecurityContext
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** RFC 0006 §7.6: checks evaluate as the permitted request's principal and scope, and fail closed. */
class AuthorizationCheckerTest {
    private val platform = PolicyDocumentCompiler().compile(
        SnapshotKey.Platform,
        SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","policies":[""" +
                    """{"id":"read","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]},""" +
                    """{"id":"order","statements":[{"effect":"allow","action":"order:read","resource":"order/{id}"}]},""" +
                    """{"id":"control","statements":[{"effect":"allow","action":"cosec:read","resource":"doc/**"}]},""" +
                    """{"id":"rank","statements":[{"effect":"allow","action":"cosec:approve","resource":"doc/**",""" +
                    """"condition":{"eq":["subject.attributes.level","3"]}}]}],""" +
                    """"bindings":[{"id":"g:read","subject":"*","app":"*","space":"*","policy":"read"},""" +
                    """{"id":"g:order","subject":"*","app":"*","space":"*","policy":"order"},""" +
                    """{"id":"g:control","subject":"*","app":"cosec-control","space":"","policy":"control"},""" +
                    """{"id":"g:rank","subject":"*","app":"cosec-control","space":"","policy":"rank"}]}"""
                ).encodeToByteArray(),
        ),
    )

    /** Installed, except after [fail] is set. */
    private inner class Views : SnapshotViews {
        var fail = false

        override fun peek(key: ViewKey): SnapshotView? = if (fail) null else view(key)

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> {
            if (fail) {
                return CompletableFuture.failedFuture(IllegalStateException("down"))
            }
            return CompletableFuture.completedFuture(view(key))
        }

        private fun view(key: ViewKey) = SnapshotView(
            platform,
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )
    }

    private val views = Views()
    private val pipeline = PipelineFixtures.pipeline(
        authenticator = authenticator(authenticated(claims())),
        engine = CoSecFlatEngine,
        views = views,
    )
    private val checker = AuthorizationChecker(pipeline)

    private fun permitted(): RequestSecurity {
        val outcome = runBlocking { pipeline.evaluate(facts("/orders/1")) }
        outcome.proceed.assert().isTrue()
        return outcome.security!!
    }

    @Test
    fun checksAsThePermittedPrincipal() = runBlocking<Unit> {
        val security = permitted()
        checker.check(security.securityContext, "http:GET", "/orders/2").assert().isTrue()
        checker.check(security.securityContext, "http:DELETE", "/orders/2").assert().isFalse()
        checker.check(security.securityContext, "order:read", "order/42").assert().isTrue()
        checker.check(security.securityContext, "order:write", "order/42").assert().isFalse()
        checker.check(security, Action("http", "GET"), Resource("http", "/other", emptyMap())).assert().isFalse()
    }

    @Test
    fun theSecurityContextCarriesTheValidatedScope() {
        val context = permitted().securityContext
        context.principal.id.assert().isEqualTo(PipelineFixtures.PRINCIPAL.value)
        context.tenant.tenantId.assert().isEqualTo(PipelineFixtures.TENANT.value)
        context.scope!!.appId.assert().isEmpty()
        context.scope!!.roles.assert().isEmpty()
    }

    @Test
    fun decidesInTheDefaultSpaceOfAnotherApp() = runBlocking<Unit> {
        val security = permitted()
        val control = AppId("cosec-control")
        val read = Action("cosec", "read")
        val doc = Resource("doc", "tenant/a", emptyMap())
        // Bound in the control app only: not in the request's own scope, but in the control app's default space.
        checker.check(security, read, doc).assert().isFalse()
        checker.decide(security, control, read, doc).assert().isInstanceOf(Decision.Permit::class.java)
        checker.decide(security, control, Action("cosec", "write"), doc).assert().isEqualTo(Decision.Deny(null))
        views.fail = true
        checker.decide(security, control, read, doc).assert().isInstanceOf(Decision.Indeterminate::class.java)
    }

    /** RFC 0007 §7.5, round-2 review R3: a recorded principal is checked again without minting a permitted request. */
    @Test
    fun decidesAsARecordedPrincipalWithoutMintingARequest() = runBlocking<Unit> {
        val security = permitted()
        val control = AppId("cosec-control")
        val approve = Action("cosec", "approve")
        val doc = Resource("doc", "tenant/a", emptyMap())
        fun ada(level: Any) =
            AuthenticatedPrincipal(
                PrincipalId("ada"),
                PipelineFixtures.TENANT,
                MembershipSource.Provider.UNCAPPED,
                mapOf("level" to level)
            )
        checker.decide(security, control, approve, doc).assert().isEqualTo(Decision.Deny(null))
        checker.decideAs(security, ada(3), security.memberships, control, approve, doc).assert()
            .isInstanceOf(Decision.Permit::class.java)
        checker.decideAs(
            security,
            ada(2),
            security.memberships,
            control,
            approve,
            doc
        ).assert().isEqualTo(Decision.Deny(null))
        views.fail = true
        checker.decideAs(security, ada(3), security.memberships, control, approve, doc).assert()
            .isInstanceOf(Decision.Indeterminate::class.java)
        // Only the pipeline makes a RequestSecurity: what is public on it hands none out (internal names are mangled).
        RequestSecurity::class.java.methods.filter { it.returnType == RequestSecurity::class.java && '$' !in it.name }
            .map { it.name }.assert().isEmpty()
    }

    @Test
    fun failsClosed() = runBlocking<Unit> {
        val security = permitted()
        // A context the pipeline did not make, and an action without a namespace.
        checker.check(SimpleSecurityContext.anonymous(), "http:GET", "/orders/2").assert().isFalse()
        checker.check(security.securityContext, "GET", "/orders/2").assert().isFalse()
        // An unreadable snapshot is not a permit.
        views.fail = true
        checker.check(security.securityContext, "http:GET", "/orders/2").assert().isFalse()
    }
}
