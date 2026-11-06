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

package me.ahoo.cosec.runtime.attribute

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.attribute.AttributeRequest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * #1209: a check ([AuthorizationChecker], `Pipeline.check`) fetches the external attributes its own action and resource
 * need, from stage-A and stage-B providers alike, whether or not any stage-B provider is registered.
 */
class CheckAttributesTest {
    /** Answers [values] for whatever it declares, in [stage], until [failAfter] calls; records every request. */
    private class Provider(
        stage: AttributeDeclaration.Stage,
        private val values: Map<String, Any>,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : AttributeProvider {
        val requests = CopyOnWriteArrayList<Pair<AttributeRequest, Set<String>>>()

        override val attributes = values.keys.map { AttributeDeclaration(it, AttributeType.BOOL, stage) }
        override val timeout = 50.milliseconds

        override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
            requests += request to names
            check(requests.size <= failAfter) { "down" }
            return values
        }
    }

    /**
     * `GET /test` and `order:read` on any order, each only while [AUDIT] (stage A) says so; with [risk], `order:write`
     * only while [RISK] (stage B) says so too.
     */
    private class Views(compiler: PolicyDocumentCompiler, risk: Boolean) : SnapshotViews {
        private val write = """,{"id":"write","statements":[{"effect":"allow","action":"order:write",""" +
            """"resource":"order/{id}","condition":{"and":[{"eq":["$AUDIT",true]},{"eq":["$RISK",true]}]}}]}"""
        private val platform = compiler.compile(
            SnapshotKey.Platform,
            document(
                ""","policies":[""" +
                    """{"id":"test","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/test",""" +
                    """"condition":{"eq":["$AUDIT",true]}}]},""" +
                    """{"id":"read","statements":[{"effect":"allow","action":"order:read","resource":"order/{id}",""" +
                    """"condition":{"eq":["$AUDIT",true]}}]}""" + (if (risk) write else "") + "]," +
                    """"bindings":[{"id":"g:test","subject":"*","app":"*","space":"*","policy":"test"},""" +
                    """{"id":"g:read","subject":"*","app":"*","space":"*","policy":"read"}""" +
                    (if (risk) """,{"id":"g:write","subject":"*","app":"*","space":"*","policy":"write"}""" else "") + "]"
            ),
        )

        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))

        private fun document(body: String) = SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
        )
    }

    private class Setup(vararg providers: Provider) {
        private val attributes = AttributeProviders(providers.toList())
        val pipeline = PipelineFixtures.pipeline(
            authenticator = authenticator(authenticated()),
            engine = CoSecFlatEngine,
            views = Views(PolicyDocumentCompiler(attributes.catalog), attributes.declares(RISK)),
        ) {
            it.copy(options = PipelineOptions(attributeTimeout = 50.milliseconds), attributes = attributes)
        }
        val checker = AuthorizationChecker(pipeline)

        /** The security of a permitted `GET /test`. */
        fun permitted(): RequestSecurity {
            val outcome = runBlocking { pipeline.evaluate(PipelineFixtures.facts("/test")) }
            outcome.status.assert().isEqualTo(200)
            return outcome.security!!
        }

        fun decide(security: RequestSecurity, action: String, resource: String): Decision =
            runBlocking { checker.decide(security.securityContext, action, resource) }
    }

    @Test
    fun `a check reads a stage-A attribute without any stage-B provider`() {
        val audit = Provider(AttributeDeclaration.Stage.A, mapOf(AUDIT to true))
        val setup = Setup(audit)
        val security = setup.permitted()
        setup.decide(security, "http:GET", "/test").assert().isInstanceOf(Decision.Permit::class.java)
        // Asked again for the check, as stage A asks: before the scope, with the check's own action and resource.
        audit.requests.assert().hasSize(2)
        val (request, names) = audit.requests.last()
        names.assert().containsExactly(AUDIT)
        request.scope.assert().isNull()
        "${request.action.namespace}:${request.action.verb}".assert().isEqualTo("http:GET")
    }

    @Test
    fun `a check fetches for its own action and resource`() {
        val audit = Provider(AttributeDeclaration.Stage.A, mapOf(AUDIT to true))
        val setup = Setup(audit)
        val security = setup.permitted()
        setup.decide(security, "order:read", "order/42").assert().isInstanceOf(Decision.Permit::class.java)
        val (request, _) = audit.requests.last()
        "${request.action.namespace}:${request.action.verb}".assert().isEqualTo("order:read")
        request.resource.id.assert().isEqualTo("42")
    }

    @Test
    fun `an unrelated stage-B provider changes nothing, and a needed one is asked with the scope`() {
        val audit = Provider(AttributeDeclaration.Stage.A, mapOf(AUDIT to true))
        val risk = Provider(AttributeDeclaration.Stage.B, mapOf(RISK to true))
        val setup = Setup(audit, risk)
        val security = setup.permitted()
        risk.requests.assert().isEmpty()
        setup.decide(security, "http:GET", "/test").assert().isInstanceOf(Decision.Permit::class.java)
        risk.requests.assert().isEmpty()
        setup.decide(security, "order:write", "order/42").assert().isInstanceOf(Decision.Permit::class.java)
        val (request, names) = risk.requests.single()
        names.assert().containsExactly(RISK)
        request.scope.assert().isEqualTo(security.scope)
    }

    @Test
    fun `a check whose stage-A attribute cannot be fetched fails closed`() {
        val setup = Setup(Provider(AttributeDeclaration.Stage.A, mapOf(AUDIT to true), failAfter = 1))
        val security = setup.permitted()
        val decision = setup.decide(security, "http:GET", "/test") as Decision.Indeterminate
        (decision.cause as Cause.AttributeUnavailable).attributes.assert().containsExactly(AUDIT)
    }

    private companion object {
        const val AUDIT = "ext.audit.allowed"
        const val RISK = "ext.risk.low"
    }
}
