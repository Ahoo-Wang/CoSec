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
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.claims
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
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
                    """{"id":"order","statements":[{"effect":"allow","action":"order:read","resource":"order/{id}"}]}],""" +
                    """"bindings":[{"id":"g:read","subject":"*","app":"*","space":"*","policy":"read"},""" +
                    """{"id":"g:order","subject":"*","app":"*","space":"*","policy":"order"}]}"""
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
