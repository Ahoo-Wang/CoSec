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

package me.ahoo.cosec.gateway

import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * IDN-8, R1-4 of #1194's review: the `Cookie` headers a gateway forwards, decoded again by the decoders the gateway's
 * own server and a backend use (Netty's `ServerCookieDecoder`, strict and lax, and one that also separates cookies at
 * `,`), name no `authorization` cookie however it was cased, spaced, quoted, repeated or spread over several headers;
 * and every other cookie decodes as it did.
 */
class CookieTokenPropertyTest {
    // propertyStress raises the count (RFC 0008 §3.5).
    private val iterations = maxOf(2_000, Integer.getInteger("cosec.property.iterations", 0))

    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val permit = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
            FlatEvaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
                ),
                null,
            )
    }

    private val pipeline = Pipeline(
        PipelineComponents(
            Authenticator { AuthnResult.Anonymous },
            SuspendingSnapshotSource(Views),
            engine = permit
        )
    )
    private val filter = PipelineGatewayFilter(pipeline)
    private val route = Route.async().id("r").uri(URI.create("lb://orders")).predicate { true }.build()

    private val names = listOf(
        "authorization", "Authorization", "AUTHORIZATION", "aUtHoRiZaTiOn",
        "theme", "sid", "a", "authorizations", "xauthorization", "auth",
        // RFC 2965 attributes: Netty skips `$`-named pairs only in a header that starts with `$Version` (R2-6).
        "\$Version", "\$version", "\$Path", "\$Domain",
    )
    private val values = listOf("t", "dark", "\"quoted\"", "\"\"", "", "a b", "x=y", "\"un", "1 ")
    private val around = listOf("", "", " ", "  ", "\t")
    private val separators = listOf("; ", ";", " ; ", ";\t", ";;", "; ;")

    /** A generated header and whether it hides a cookie behind a `,`, which removes the whole pair. */
    private class Header(val text: String, val commas: Boolean)

    private fun Random.pair(): Pair<String, Boolean> {
        val name = around.random(this) + names.random(this) + around.random(this)
        val pair = if (nextInt(10) == 0) name else "$name=${values.random(this)}"
        // A decoder that also separates cookies at `,` reads two here.
        return if (nextInt(8) == 0) "$pair, ${names.random(this)}=${values.random(this)}" to true else pair to false
    }

    private fun Random.header(): Header {
        val pairs = List(nextInt(1, 6)) { pair() }
        val text = around.random(this) +
            pairs.joinToString("") { (pair, _) -> pair + separators.random(this) }.dropLast(nextInt(2)) +
            around.random(this)
        return Header(text, pairs.any { it.second })
    }

    private fun forwarded(headers: List<String>): List<String>? {
        val request = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/orders"))
            .header(HttpHeaders.COOKIE, *headers.toTypedArray())
        val exchange = MockServerWebExchange.from(request)
        exchange.attributes[GATEWAY_ROUTE_ATTR] = route
        var sent: List<String>? = null
        var reached = false
        filter.filter(exchange) { next ->
            sent = next.request.headers[HttpHeaders.COOKIE]
            reached = true
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        // A refused request forwards nothing, which would satisfy every property below.
        reached.assert().isTrue()
        return sent
    }

    private fun isToken(name: String) = name.trim().equals("authorization", ignoreCase = true)

    private fun strict(header: String) = ServerCookieDecoder.STRICT.decodeAll(header).map { it.name() to it.value() }

    private fun lax(header: String) = ServerCookieDecoder.LAX.decodeAll(header).map { it.name() to it.value() }

    /**
     * The empty pairs of [header], decoder aside: those of nothing but whitespace and `,`, a trailing one apart (no
     * pair follows it). RFC 6265 has none, and Python's `SimpleCookie` stops at the first (R3-2).
     */
    private fun emptyPairs(header: String): Int =
        header.split(';').dropLast(1).count { pair -> pair.all { it.isWhitespace() || it == ',' } }

    private fun hasCookie(header: String): Boolean = header.split(';').any { pair ->
        pair.any {
            !it.isWhitespace() && it != ','
        }
    }

    /** A decoder of the older kind: `;` and `,` both separate cookies, and names are trimmed. */
    private fun splitting(header: String) = header.split(';', ',').map { it.substringBefore('=').trim() }

    @Test
    fun `no forwarded cookie header names the token, and every other cookie decodes as it did`() {
        // The review's case: trimming the leading space made the header start with `$Version`, and Netty then dropped
        // the `$Version` and `$Path` cookies it read before.
        val example = listOf(" \$Version=1; authorization=t; \$Path=/; a=b")
        forwarded(example)!!.flatMap(::strict).assert()
            .isEqualTo(listOf("\$Version" to "1", "\$Path" to "/", "a" to "b"))
        val random = Random(20261103)
        repeat(iterations) { iteration ->
            val headers = List(random.nextInt(1, 4)) { random.header() }
            val original = headers.map { it.text }
            val sent = forwarded(original)
            try {
                // The gateway's fast path asks namesCookieToken, without allocating: it holds exactly when the rewrite
                // changes the header.
                original.forEach { header ->
                    pipeline.namesCookieToken(header).assert()
                        .describedAs(header).isEqualTo(pipeline.withoutCookieToken(header) != header)
                }
                // R3-2, R3-6: one header at a time, the rewrite makes no empty pair its original did not have, and
                // leaves no header without a cookie.
                original.forEach { header ->
                    forwarded(listOf(header))?.single()?.let { rewritten ->
                        emptyPairs(rewritten).assert().isLessThanOrEqualTo(emptyPairs(header))
                    }
                }
                sent?.forEach { header ->
                    hasCookie(header).assert().isTrue()
                    strict(header).none { isToken(it.first) }.assert().isTrue()
                    lax(header).none { isToken(it.first) }.assert().isTrue()
                    splitting(header).none(::isToken).assert().isTrue()
                }
                // Every other cookie decodes as it did, under either decoder: same names, values and order (R2-6).
                for (decode in listOf(::lax, ::strict)) {
                    val kept = sent.orEmpty().flatMap(decode)
                    val expected = original.flatMap(decode).filterNot { isToken(it.first) }
                    if (headers.none { it.commas }) {
                        kept.assert().isEqualTo(expected)
                    } else {
                        // A pair that hid the token behind a `,` went whole: what is left is as it was, in order.
                        var at = 0
                        kept.forEach { cookie ->
                            while (at < expected.size && expected[at] != cookie) at++
                            (at < expected.size).assert().isTrue()
                            at++
                        }
                    }
                }
                if (original.none { it.contains("authorization", ignoreCase = true) }) {
                    sent.assert().isEqualTo(original)
                }
            } catch (e: AssertionError) {
                throw AssertionError("Case $iteration: $original -> $sent", e)
            }
        }
    }
}
