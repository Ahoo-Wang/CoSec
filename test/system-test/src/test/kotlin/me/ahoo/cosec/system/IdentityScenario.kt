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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Scenario 1, the identity through the gateway (IDN-7, IDN-8, PIP-10, PIP-5, PIP-14, PIP-25): what a real gateway sends
 * a real service in `cosec.pipeline.mode=gateway`, which no lower test sees: there, the header comes from the test's own
 * encoder. The service (`test/system-behind`) answers what reached it.
 */
@Tag("system")
@Order(10)
@ExtendWith(TranscriptExtension::class)
class IdentityScenario {
    @Test
    fun `a permitted request reaches the service with the identity the gateway verified, and the path it decided on`() {
        val token = Token.issue("alice", "identity-tenant")
        val echo = Http.get(at("/behind/orders/42"), token.bearer)
        echo.status.assert().isEqualTo(200)
        val received = echo.json()
        received["rawPath"].assert().isEqualTo("/behind/orders/42")
        val identities = received.headers()["cosec-identity"].orEmpty()
        identities.assert().hasSize(1)
        val identity = ForwardedIdentity.decode(identities.single())
        Transcript.note("decoded CoSec-Identity: $identity")
        identity["v"].assert().isEqualTo(1)
        identity["tenant"].assert().isEqualTo("identity-tenant")
        identity["sub"].assert().isEqualTo("alice")
        val auth = identity.member("auth")
        auth["method"].assert().isEqualTo("jwt")
        // Whose the credential is, which CoSec's writer always states (IDN-44): alice signed in as a person.
        auth["kind"].assert().isEqualTo("person")
        auth["jti"].assert().isEqualTo(token.jti)
        (auth["exp"] as Number).toLong().assert().isEqualTo(token.expiresAt.epochSecond)
        (auth["iat"] as Number?)?.toLong().assert().isEqualTo(token.issuedAt.epochSecond)
        // A token without a session (the format's session is optional) still decodes at the service: it has none.
        auth.keys.assert().doesNotContain("session")
        val security = received.member("security")
        security["principal"].assert().isEqualTo("alice")
        security["tenant"].assert().isEqualTo("identity-tenant")
        security["authenticated"].assert().isEqualTo(true)
        security["credentialId"].assert().isEqualTo(token.jti)
        security["gatewayRequestId"].assert().isEqualTo(echo.header("CoSec-Request-Id"))
    }

    @Test
    fun `a token CoSec's login issued reaches the service with its session, which a service relies on`() {
        val signedIn = SignIn.of("bella", "identity-tenant")
        val claims = signedIn.claims
        for (gateway in SystemTopology.gateways) {
            val echo = Http.get(gateway.resolve("/behind/orders/42"), signedIn.bearer)
            echo.status.assert().isEqualTo(200)
            val identity = ForwardedIdentity.decode(echo.json().headers().getValue("cosec-identity").single())
            Transcript.note("decoded CoSec-Identity of a session token: $identity")
            identity["sub"].assert().isEqualTo("bella")
            val auth = identity.member("auth")
            // CoSec's login declared bella a person, its token says so, and the gateway forwards it (IDN-45, IDN-46).
            auth["kind"].assert().isEqualTo("person")
            auth["jti"].assert().isEqualTo(claims["jti"])
            (auth["iat"] as Number).toLong().assert().isEqualTo((claims.getValue("iat") as Number).toLong())
            val session = auth.member("session")
            session["sid"].assert().isEqualTo(claims.getValue("sid"))
            (session["auth_time"] as Number).toLong().assert().isEqualTo(
                (claims.getValue("auth_time") as Number).toLong()
            )
            (session["generation"] as Number).toLong().assert().isEqualTo(
                (claims.getValue("cosec.generation") as Number).toLong()
            )
        }
    }

    /**
     * `IDN-46`: a refreshed pair keeps the kind of the token it was refreshed from, and a token that does not say whose it
     * is, as an earlier 8.0 preview issued it, reaches the service as a workload's, never refused.
     */
    @Test
    fun `a refreshed token stays a person's, and a token without its kind is forwarded as a workload's`() {
        val refreshed = SignIn.pairOf(SignIn.refresh(SignIn.of("bella", "identity-tenant")))
        forwardedKind(refreshed.bearer).assert().isEqualTo("person")
        forwardedKind(Token.issue("alice", "identity-tenant", kind = null).bearer).assert().isEqualTo("workload")
        forwardedKind(Token.issue("alice", "identity-tenant", kind = "workload").bearer).assert().isEqualTo("workload")
    }

    /** The `auth.kind` of the identity the gateway forwards for a request with [bearer]. */
    private fun forwardedKind(bearer: Pair<String, String>): Any? {
        val echo = Http.get(at("/behind/orders/42"), bearer)
        echo.status.assert().describedAs("$echo").isEqualTo(200)
        val identity = ForwardedIdentity.decode(echo.json().headers().getValue("cosec-identity").single())
        return identity.member("auth")["kind"]
    }

    @Test
    fun `the client's credentials stay at the gateway, and its other cookies go on`() {
        val token = Token.issue("alice", "identity-tenant")
        val echo = Http.get(
            at("/behind/orders/42?authorization=${token.value}&page=2"),
            token.bearer,
            "Cookie" to "theme=dark; authorization=${token.value}; lang=en",
        )
        echo.status.assert().isEqualTo(200)
        val received = echo.json()
        received.headers().keys.assert().doesNotContain("authorization")
        received["query"].assert().isEqualTo(listOf("page"))
        received["cookies"].assert().isEqualTo(listOf("lang", "theme"))
        received.headers()["cookie"].orEmpty().joinToString(";").assert().doesNotContain(token.value)
    }

    @Test
    fun `an API key header stays at the gateway too, on a request it permits as anonymous`() {
        val echo = Http.get(at("/behind/public/page"), "X-API-Key" to "system-test-not-a-key")
        echo.status.assert().isEqualTo(200)
        val received = echo.json()
        received.headers().keys.assert().doesNotContain("x-api-key")
        received.member("security")["authenticated"].assert().isEqualTo(false)
    }

    @Test
    fun `a method override header never arrives, so the service runs the method the gateway decided`() {
        val token = Token.issue("alice", "identity-tenant")
        for (gateway in SystemTopology.gateways) {
            val echo = Http.send(
                "POST",
                gateway.resolve("/behind/orders/42"),
                listOf(
                    "Authorization" to token.bearer.second,
                    "X-HTTP-Method-Override" to "DELETE",
                    "x-http-method" to "DELETE",
                    "X-Method-Override" to "DELETE",
                    "X-Trace" to "kept",
                ),
                "{}",
            )
            echo.status.assert().isEqualTo(200)
            val received = echo.json()
            received["method"].assert().isEqualTo("POST")
            received.headers().keys.assert().doesNotContain(
                "x-http-method-override",
                "x-http-method",
                "x-method-override"
            )
            received.headers()["x-trace"].assert().isEqualTo(listOf("kept"))
        }
    }

    @Test
    fun `a CoSec-Identity the client sends never arrives`() {
        val forged = forgedIdentity()
        val anonymous = Http.get(at("/behind/public/page"), "CoSec-Identity" to forged)
        anonymous.status.assert().isEqualTo(200)
        anonymous.json().headers().keys.assert().doesNotContain("cosec-identity")
        anonymous.json().member("security")["authenticated"].assert().isEqualTo(false)

        val token = Token.issue("alice", "identity-tenant")
        val signedIn = Http.get(at("/behind/orders/42"), token.bearer, "CoSec-Identity" to forged)
        signedIn.status.assert().isEqualTo(200)
        val identities = signedIn.json().headers()["cosec-identity"].orEmpty()
        identities.assert().hasSize(1)
        ForwardedIdentity.decode(identities.single())["sub"].assert().isEqualTo("alice")
        signedIn.json().member("security")["principal"].assert().isEqualTo("alice")
    }

    @Test
    fun `a route that does not receive the identity gets none, nor the credentials`() {
        val token = Token.issue("alice", "identity-tenant")
        val echo = Http.get(at("/plain/orders/42"), token.bearer)
        echo.status.assert().isEqualTo(200)
        val received = echo.json()
        received.headers().keys.assert().doesNotContain("cosec-identity", "authorization")
        received.member("security")["authenticated"].assert().isEqualTo(false)
    }

    @Test
    fun `refusals are the problem types of the contract`() {
        Contract.assertProblem(Http.get(at("/behind/orders/42")), "unauthorized")
        Contract.assertProblem(
            Http.get(at("/behind/denied/42"), Token.issue("alice", "identity-tenant").bearer),
            "forbidden"
        )
        val expired = Token.issue(
            "alice",
            "identity-tenant",
            issuedAt = Instant.now().minus(Duration.ofHours(1)),
            validity = Duration.ofMinutes(5)
        )
        Contract.assertProblem(Http.get(at("/behind/orders/42"), expired.bearer), "token-expired")
        val foreign = Token.issue("alice", "identity-tenant", secret = "not-the-gateways-secret-0123456789abcdef")
        Contract.assertProblem(Http.get(at("/behind/orders/42"), foreign.bearer), "token-invalid")
    }

    @Test
    fun `a path a pattern could misread is refused at the gateway and never reaches the service`() {
        val token = Token.issue("alice", "identity-tenant")
        for (path in listOf(
            "/behind/orders/%2e%2e/denied/42",
            "/behind/orders%2Fx",
            "/behind//denied/42",
            "/behind/orders/%5C42"
        )) {
            Contract.assertProblem(Http.get(at(path), token.bearer), "invalid-request")
        }
    }

    private fun at(pathAndQuery: String): URI = URI.create("${SystemTopology.gateway}$pathAndQuery")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.headers(): Map<String, List<String>> = this["headers"] as Map<String, List<String>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.member(name: String): Map<String, Any?> =
        requireNotNull(this[name] as Map<String, Any?>?) { "no $name in $this" }

    /** An identity in the documented format that names someone the gateway never verified. */
    private fun forgedIdentity(): String {
        val now = Instant.now().epochSecond
        val identity = mapOf(
            "v" to 1,
            "tenant" to "identity-tenant",
            "sub" to "mallory",
            "scope" to mapOf("app" to "", "space" to ""),
            "roles" to listOf("admin"),
            "auth" to mapOf("method" to "jwt", "kind" to "person", "jti" to "forged", "exp" to now + 600, "iat" to now),
            "iat" to now,
            "rid" to "forged",
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.mapper.writeValueAsBytes(identity))
    }
}
