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
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * The host's attributes reach the engine (RFC 0005 §3.2, RFC 0006 §5.4): through the pipeline, with a real document
 * and the real engine, a DENY on a header wins over an ALLOW, and an ALLOW gated on a request attribute or the
 * origin's host permits. Without them every such condition reads as absent: the DENY lapses and the ALLOW never
 * permits.
 */
class HostAttributesPipelineTest {
    private class Views(platform: String) : SnapshotViews {
        private val compiler = PolicyDocumentCompiler()
        private val platform = compiler.compile(SnapshotKey.Platform, document(platform))
        private val tenant = compiler.compile(SnapshotKey.Tenant(PipelineFixtures.TENANT.value), document(""))

        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { tenant },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))

        private fun document(body: String) = SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
        )
    }

    private val pipeline = PipelineFixtures.pipeline(
        authenticator = authenticator(authenticated()),
        engine = CoSecFlatEngine,
        views = Views(PLATFORM),
    )

    private fun facts(headers: Map<String, List<String>> = emptyMap(), attributes: Map<String, String> = emptyMap()) =
        HttpFacts(
            method = "GET",
            rawPath = "/orders",
            rawQuery = null,
            headers = Headers.of(headers),
            cookies = Cookies.EMPTY,
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
            requestAttributes = attributes,
        )

    private fun evaluate(facts: HttpFacts): Outcome = runBlocking { pipeline.evaluate(facts) }

    private val Outcome.decision: Decision get() = (basis as Basis.Decided).decision

    private val Outcome.policy: PolicyId?
        get() = when (val decision = decision) {
            is Decision.Permit -> (decision.by as StatementRef.Policy).policy
            is Decision.Deny -> (decision.by as? StatementRef.Policy)?.policy
            is Decision.Indeterminate -> null
        }

    @Test
    fun aRequestAttributeGatesAnAllow() {
        val permitted = evaluate(facts(attributes = mapOf(ROUTE_ID to "orders")))
        permitted.status.assert().isEqualTo(200)
        permitted.policy.assert().isEqualTo(PolicyId("by-route"))
        evaluate(facts(attributes = mapOf(ROUTE_ID to "billing"))).status.assert().isEqualTo(403)
        evaluate(facts()).decision.assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun aDenyOnAHeaderWinsOverTheAllow() {
        val denied = evaluate(facts(mapOf("X-Debug" to listOf("1")), mapOf(ROUTE_ID to "orders")))
        denied.status.assert().isEqualTo(403)
        denied.policy.assert().isEqualTo(PolicyId("no-debug"))
        evaluate(facts(mapOf("X-Debug" to listOf("0")), mapOf(ROUTE_ID to "orders"))).status.assert().isEqualTo(200)
    }

    @Test
    fun theOriginsHostGatesAnAllow() {
        val permitted = evaluate(facts(mapOf("Origin" to listOf("https://app.example.com:8443"))))
        permitted.status.assert().isEqualTo(200)
        permitted.policy.assert().isEqualTo(PolicyId("by-origin"))
        evaluate(facts(mapOf("Origin" to listOf("https://other.example.com")))).status.assert().isEqualTo(403)
        evaluate(facts(mapOf("Origin" to listOf("not a uri")))).status.assert().isEqualTo(403)
    }

    /** A header sent twice with different values has none: the DENY reading it errs, so the request is 503. */
    @Test
    fun aHeaderWithDifferentValuesIsIndeterminate() {
        val ambiguous = evaluate(facts(mapOf("X-Debug" to listOf("0", "1")), mapOf(ROUTE_ID to "orders")))
        ambiguous.status.assert().isEqualTo(503)
        ambiguous.basis.assert().isInstanceOf(Basis.Unavailable::class.java)
        evaluate(
            facts(mapOf("X-Debug" to listOf("0", "0")), mapOf(ROUTE_ID to "orders"))
        ).status.assert().isEqualTo(200)
    }

    /** Hosts are case-insensitive: the origin's host is read in lower case. */
    @Test
    fun theOriginsHostIsLowerCase() {
        evaluate(facts(mapOf("Origin" to listOf("https://App.Example.COM")))).status.assert().isEqualTo(200)
    }

    /** A check of a permitted request reads that request's attributes, as its decision did. */
    @Test
    fun aCheckReadsTheRequestsAttributes() {
        val security = checkNotNull(evaluate(facts(attributes = mapOf(ROUTE_ID to "orders"))).security)
        val checker = AuthorizationChecker(pipeline)
        runBlocking { checker.check(security, Action("http", "GET"), Resource("http", "/orders/1", emptyMap())) }
            .assert().isTrue()
    }

    private companion object {
        const val ROUTE_ID = "route.id"

        const val PLATFORM = ""","policies":[""" +
            """{"id":"by-route","statements":[{"effect":"allow","action":"*",""" +
            """"condition":{"eq":["request.attributes.route.id","orders"]}}]},""" +
            """{"id":"by-origin","statements":[{"effect":"allow","action":"*",""" +
            """"condition":{"eq":["http.origin.host","app.example.com"]}}]},""" +
            """{"id":"no-debug","statements":[{"effect":"deny","action":"*",""" +
            """"condition":{"eq":["http.header.X-Debug","1"]}}]}],""" +
            """"bindings":[""" +
            """{"id":"by-route","subject":"*","app":"*","space":"*","policy":"by-route"},""" +
            """{"id":"by-origin","subject":"*","app":"*","space":"*","policy":"by-origin"},""" +
            """{"id":"no-debug","subject":"*","app":"*","space":"*","policy":"no-debug"}]"""
    }
}
