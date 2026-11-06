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
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.Unavailability
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * #1208 (`ATR-8`) through the pipeline: a platform boundary whose condition reads a stage-B attribute is decided on what
 * the provider answers, since the plan holds it; one that does not hold the request's tenant is not fetched.
 */
class BoundaryAttributesPipelineTest {
    /** `ext.geo.inFence`, in stage B: fetched only when the plan names it. */
    private class Geo(private val answer: () -> Map<String, Any>) : AttributeProvider {
        val calls = AtomicInteger()

        override val attributes = listOf(AttributeDeclaration(IN_FENCE, AttributeType.BOOL))
        override val timeout = 50.milliseconds

        override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
            calls.incrementAndGet()
            return answer()
        }
    }

    /** Anyone may do anything; the default boundary [FENCE] holds tenants to the fence, `open` holds nothing back. */
    private class Views(
        compiler: PolicyDocumentCompiler,
        fenceOnMissing: String?,
        tenantBoundary: String?
    ) : SnapshotViews {
        private val platform = compiler.compile(
            SnapshotKey.Platform,
            document(
                ""","boundaries":{"default":"$FENCE"},"policies":[""" +
                    """{"id":"all","statements":[{"effect":"allow","action":"*"}]},""" +
                    """{"id":"$FENCE","boundary":true,"statements":[{"effect":"allow","action":"*",""" +
                    """"condition":{"eq":["$IN_FENCE",true]}""" +
                    (fenceOnMissing?.let { ""","onMissing":"$it"""" } ?: "") + "}]}," +
                    """{"id":"open","boundary":true,"statements":[{"effect":"allow","action":"*"}]}],""" +
                    """"bindings":[{"id":"g:all","subject":"*","app":"*","space":"*","policy":"all"}]"""
            ),
        )
        private val tenant = compiler.compile(
            SnapshotKey.Tenant(PipelineFixtures.TENANT.value),
            document(tenantBoundary?.let { ""","boundary":"$it"""" } ?: ""),
        )

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

    private fun evaluate(geo: Geo, fenceOnMissing: String? = null, tenantBoundary: String? = null): Outcome {
        val attributes = AttributeProviders(listOf(geo))
        val pipeline = PipelineFixtures.pipeline(
            authenticator = authenticator(authenticated()),
            engine = CoSecFlatEngine,
            views = Views(PolicyDocumentCompiler(attributes.catalog), fenceOnMissing, tenantBoundary),
        ) {
            it.copy(options = PipelineOptions(attributeTimeout = 50.milliseconds), attributes = attributes)
        }
        return runBlocking { pipeline.evaluate(PipelineFixtures.facts()) }
    }

    @Test
    fun `a boundary reading a stage-B attribute is decided on the provider's answer`() {
        val inside = Geo { mapOf(IN_FENCE to true) }
        evaluate(inside).status.assert().isEqualTo(200)
        inside.calls.get().assert().isEqualTo(1)
        evaluate(Geo { mapOf(IN_FENCE to false) }).status.assert().isEqualTo(403)
    }

    @Test
    fun `a provider that fails follows the boundary statement's onMissing, else fails closed`() {
        val down = Geo { error("geo is down") }
        val outcome = evaluate(down)
        outcome.status.assert().isEqualTo(503)
        val reason = (outcome.basis as Basis.Unavailable).reason as Unavailability.Indeterminate
        reason.cause.assert().isInstanceOf(Cause.BoundaryUnavailable::class.java)
        // `nomatch` makes the statement a miss: the boundary holds nothing in, so the permit is a deny.
        evaluate(down, fenceOnMissing = "nomatch").status.assert().isEqualTo(403)
    }

    @Test
    fun `a boundary that does not hold the request's tenant is not fetched`() {
        val geo = Geo { mapOf(IN_FENCE to false) }
        evaluate(geo, tenantBoundary = "open").status.assert().isEqualTo(200)
        geo.calls.get().assert().isZero()
    }

    private companion object {
        const val IN_FENCE = "ext.geo.inFence"
        const val FENCE = "fence"
    }
}
