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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.ip2region

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Round 2 M5, decided by the pipeline: a geo block written the natural way (`DENY when country in [...]`) is not stepped
 * around over IPv6. Before, an IPv6 client's country was absent, the DENY did not match, and the ALLOW permitted it; now
 * it is unavailable, and the DENY makes the request 503.
 */
class GeoBlockDecisionTest {
    private val providers = AttributeProviders(listOf(Ip2RegionAttributeProvider()))

    private fun document(onMissing: String?): CompiledDocument = PolicyDocumentCompiler(providers.catalog).compile(
        SnapshotKey.Platform,
        SnapshotDocument(
            2,
            1,
            Instant.parse("2026-11-14T00:00:00Z"),
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-11-14T00:00:00Z","policies":[""" +
                    """{"id":"open","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/**"}]},""" +
                    """{"id":"geo-block","statements":[{"effect":"deny","action":"*","resource":"http:path:/**",""" +
                    (
                        onMissing?.let {
                            """"onMissing":"$it","onMissingConfirmed":""" +
                                """{"by":{"tenant":"(platform)","principal":"ops"},"change":"c-1"},"""
                        } ?: ""
                        ) +
                    """"condition":{"in":["ext.ip2region.country",["中国"]]}}]}],""" +
                    """"bindings":[{"id":"b1","subject":"*","app":"*","space":"*","policy":"open"},""" +
                    """{"id":"b2","subject":"*","app":"*","space":"*","policy":"geo-block"}]}"""
                ).encodeToByteArray(),
        ),
    )

    private fun pipeline(document: CompiledDocument) = Pipeline(
        PipelineComponents(
            Authenticator { AuthnResult.Anonymous },
            SuspendingSnapshotSource(object : SnapshotViews {
                override fun peek(key: ViewKey) = SnapshotView(
                    document,
                    null,
                    key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
                )

                override fun load(key: ViewKey) = CompletableFuture.completedFuture(peek(key))
            }),
            attributes = providers,
        )
    )

    private fun Pipeline.status(remote: String): Int = runBlocking {
        evaluate(HttpFacts("GET", "/page", null, Headers.EMPTY, Cookies.EMPTY, remote, HostScope(null))).status
    }

    @Test
    fun `a geo block is not stepped around over IPv6`() {
        val pipeline = pipeline(document(null))
        pipeline.status("101.228.87.88").assert().isEqualTo(401)
        pipeline.status("240e:3b0::1").assert().isEqualTo(503)
        // A private address the database covers has no country: absent, so the DENY does not match it.
        pipeline.status("10.0.0.1").assert().isEqualTo(200)
        pipeline.status("203.0.113.7").assert().isEqualTo(200)
    }

    /** An author who wants an unknown location let through says so: `onMissing: nomatch`. */
    @Test
    fun `onMissing nomatch lets an unlocatable address through`() {
        pipeline(document("nomatch")).status("240e:3b0::1").assert().isEqualTo(200)
    }
}
