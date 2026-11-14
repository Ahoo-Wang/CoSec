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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What [HttpAttributes] answers for each host attribute (RFC 0005 §3.2). */
class HttpAttributesTest {
    private val attributes = HttpAttributes(
        Headers.of(
            mapOf(
                "X-Debug" to listOf("1"),
                "Origin" to listOf("https://App.Example.com:8443"),
                "Referer" to listOf("https://WWW.example.com/page?q=1"),
                "X-Twice" to listOf("a", "b"),
                "X-Same" to listOf("a", "a"),
            )
        ),
        mapOf("cosec.audit.routeId" to "orders"),
    )

    private fun string(kind: Kind, name: String? = null): String? =
        attributes.string(if (name == null) Attribute.of(kind) else Attribute.of(kind, name))

    @Test
    fun headersOriginAndRefererAndTheirHosts() {
        string(Kind.HTTP_HEADER, "x-debug").assert().isEqualTo("1")
        string(Kind.HTTP_HEADER, "x-absent").assert().isNull()
        string(Kind.HTTP_HEADER, "x-same").assert().isEqualTo("a")
        string(Kind.HTTP_ORIGIN).assert().isEqualTo("https://App.Example.com:8443")
        string(Kind.HTTP_REFERER).assert().isEqualTo("https://WWW.example.com/page?q=1")
        string(Kind.HTTP_ORIGIN_HOST).assert().isEqualTo("app.example.com")
        string(Kind.HTTP_REFERER_HOST).assert().isEqualTo("www.example.com")
        string(Kind.REQUEST_ATTRIBUTE, "cosec.audit.routeId").assert().isEqualTo("orders")
        string(Kind.SUBJECT_ID).assert().isNull()
    }

    /**
     * Round 2 review L3: `evil.com.` is the DNS name `evil.com`, so its host is `evil.com`, and a DENY on the host or its
     * labels cannot be stepped around with the root label's dot. Only one dot: `evil.com..` is no host name.
     */
    @Test
    fun aHostReadsWithoutTheRootLabelsDot() {
        fun host(origin: String) =
            HttpAttributes(Headers.of(mapOf("Origin" to listOf(origin)))).string(Attribute.of(Kind.HTTP_ORIGIN_HOST))
        host("https://API.Evil.com.").assert().isEqualTo("api.evil.com")
        host("https://api.evil.com.:8443").assert().isEqualTo("api.evil.com")
        host("https://api.evil.com").assert().isEqualTo("api.evil.com")
        host("https://api.evil.com..").assert().isNotEqualTo("api.evil.com")
        HttpAttributes(Headers.of(mapOf("Referer" to listOf("https://www.evil.com./a"))))
            .string(Attribute.of(Kind.HTTP_REFERER_HOST)).assert().isEqualTo("www.evil.com")
        uriHost("https://evil.com.").assert().isEqualTo("evil.com")
        uriHost("not a uri").assert().isNull()
    }

    /**
     * `PIP-12` for the control plane's simulations (D2): a header the view's documents read, sent with different values,
     * is the request's ambiguous header, whichever statement reads it; identical repeats and unread headers are not.
     */
    @Test
    fun aRepeatedHeaderTheViewReadsIsAmbiguous() {
        val platform = PolicyDocumentCodec.decode(
            DocumentKind.PLATFORM,
            "",
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-27T00:00:00Z","policies":[{"id":"p","statements":""" +
                    """[{"effect":"allow","action":"order:read","condition":{"eq":["http.header.x-twice","a"]}}]}]}"""
                ).encodeToByteArray(),
        )
        val view = SnapshotView(PolicyDocumentCompiler.forPlatform(platform).compile(platform), null, null)
        attributes.ambiguousHeader(view).assert().isEqualTo("x-twice")
        HttpAttributes(Headers.of(mapOf("X-Twice" to listOf("a", "a")))).ambiguousHeader(view).assert().isNull()
        HttpAttributes(Headers.of(mapOf("X-Other" to listOf("a", "b")))).ambiguousHeader(view).assert().isNull()
    }

    /** RC1-21: the host's API key header never reads, even through a document a compiler was not told about. */
    @Test
    fun theApiKeyHeaderIsNeverRead() {
        val headers = Headers.of(mapOf("X-Acme-Key" to listOf("ak_secret"), "X-Debug" to listOf("1")))
        val keyed = HttpAttributes(headers, credentialHeader = "X-Acme-Key")
        val error = assertThrows<CredentialHeaderException> {
            keyed.string(Attribute.of(Kind.HTTP_HEADER, "x-acme-key"))
        }
        error.message.assert().doesNotContain("ak_secret")
        keyed.string(Attribute.of(Kind.HTTP_HEADER, "x-debug")).assert().isEqualTo("1")
        HttpAttributes(headers).string(Attribute.of(Kind.HTTP_HEADER, "x-acme-key")).assert().isEqualTo("ak_secret")
    }

    @Test
    fun aHeaderWithDifferentValuesHasNone() {
        val error = assertThrows<AmbiguousHeaderException> { string(Kind.HTTP_HEADER, "x-twice") }
        error.header.assert().isEqualTo("x-twice")
    }

    @Test
    fun anOriginThatIsNotAUriHasNoHost() {
        val malformed = HttpAttributes(
            Headers.of(mapOf("Origin" to listOf("not a uri"), "Referer" to listOf("/relative"))),
        )
        malformed.string(Attribute.of(Kind.HTTP_ORIGIN_HOST)).assert().isNull()
        malformed.string(Attribute.of(Kind.HTTP_REFERER_HOST)).assert().isNull()
    }

    @Test
    fun otherTypesAreAbsent() {
        attributes.bool(Attribute.of(Kind.SUBJECT_AUTHENTICATED)).assert().isNull()
        attributes.stringSet(Attribute.of(Kind.SUBJECT_ROLES)).assert().isNull()
        attributes.ip(Attribute.of(Kind.ENVIRONMENT_IP)).assert().isNull()
        attributes.time(Attribute.of(Kind.ENVIRONMENT_TIME)).assert().isEqualTo(ConditionInput.NO_TIME)
    }

    /** Header names fold as the documents fold them: ASCII only, so `X-API-\u212Aey` is not `x-api-key`. */
    @Test
    fun `header names fold ASCII letters only`() {
        val headers = Headers.of(mapOf("X-API-\u212Aey" to listOf("forged"), "X-API-KEY" to listOf("real")))
        headers.values("x-api-key").assert().containsExactly("real")
        headers.values("x-api-\u212Aey").assert().containsExactly("forged")
    }
}
