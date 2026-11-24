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

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerifier
import java.time.Clock
import java.time.Duration

/**
 * The `CoSec-Identity` header ([NAME]) a CoSec gateway forwards to the services behind it, written by CoSec's own
 * encoder: for testing a service that runs behind a gateway (`cosec.pipeline.mode=gateway`), or calling it from a local
 * client during development, without a gateway in front.
 *
 * A test and development tool, not a gateway. A service in `gateway` mode believes whoever sends this header: in
 * production only the gateway may reach the service, and the gateway strips the header from every request a client
 * sends. Never put this module on a production classpath to make identities.
 *
 * **A header is good for a minute.** The service refuses an identity issued longer ago than its maximum age
 * (`cosec.pipeline.identity.max-age`, `60s` by default, plus `cosec.authentication.revocation.skew`, `5s`) with `401`
 * `token-expired`, however long the credential is valid: make a header per request, which is cheap, rather than one
 * for a whole test class.
 *
 * Every form reads what it wrote back as a service does before returning it, so it never hands out a header a
 * service would refuse as malformed.
 */
@ExperimentalCoSecApi
object IdentityHeader {
    /** The header's name, `CoSec-Identity`. */
    const val NAME: String = IdentityCodec.HEADER

    /**
     * The key of [ONLY_GATEWAY_CAN_REACH] alone, `cosec.pipeline.identity.only-gateway-can-reach`, whose value is `true`:
     * for a launcher that takes a property map (`mapOf(ONLY_GATEWAY_CAN_REACH_KEY to "true")`) rather than an assignment.
     * For tests only, as [ONLY_GATEWAY_CAN_REACH].
     */
    const val ONLY_GATEWAY_CAN_REACH_KEY: String = "cosec.pipeline.identity.only-gateway-can-reach"

    /**
     * The statement a Spring Boot test of a service in `gateway` mode makes, as a property assignment:
     * `cosec.pipeline.identity.only-gateway-can-reach=true`, for `@SpringBootTest(properties = [...])` or
     * `@TestPropertySource(properties = [...])`. Without it the service does not start (`STA-7`): it believes any
     * header it receives, and the operator must state that nothing but the gateway can reach it.
     *
     * For tests only. A test's service is reached by the test's own client alone, so the statement is true there. In a
     * deployment it is the operator's to make once the network makes it true (a NetworkPolicy, mutual TLS, a private
     * network), never copied from a test.
     */
    const val ONLY_GATEWAY_CAN_REACH: String = "$ONLY_GATEWAY_CAN_REACH_KEY=true"

    /** How long [forSubject]'s credential is valid by default. */
    private val DEFAULT_VALIDITY: Duration = Duration.ofHours(1)

    private const val INTERNAL_ERROR = 500

    /**
     * The header's value for [identity], exactly as a gateway encodes it. The header is as old as
     * [Identity.issuedAt]: a service refuses it once that is longer ago than its maximum age (above).
     *
     * @throws IllegalArgumentException when a service would refuse the header as malformed, naming the member (an empty
     * or blank tenant or subject, a role with an unpaired surrogate, a credential issued after it expires), or when it cannot be
     * written at all (a credential that never expires, a claim that is not a string, number or boolean, more than 8 KB)
     */
    @JvmStatic
    fun of(identity: Identity): String {
        val header = try {
            IdentityCodec.encode(identity)
        } catch (e: IdentityOverflowException) {
            throw IllegalArgumentException("The identity cannot be written: ${e.message}", e)
        } catch (e: IdentityFormatException) {
            throw IllegalArgumentException("The identity cannot be written: ${e.message}", e)
        }
        return readBack(header)
    }

    /**
     * What a gateway forwards for a request with CoSec's own token of [subject] in [tenant], decided in [app] and
     * [space] with [roles] effective there: the shape of the common case, made now by [clock]. CoSec fills in what the
     * gateway would: `auth.method` `jwt`, the token's id (`auth.jti`), when it was issued (`auth.iat`) and expires (now
     * plus [validFor]), the header's issue time (`iat`) and request id (`rid`).
     *
     * It also writes the token's session (`auth.session`), as a gateway does for CoSec's own token. That session is
     * made up: its id comes from CoSec's random id generator, its login time is now and its generation 1, so no login
     * created it. A service that issues from the session, a tenant switch for instance, then issues from a session no
     * family knows; use [withToken] with a token CoSec issued when that matters. It states no other memberships
     * (`memberships` empty, `IDN-39`), as a gateway does for a principal whose roles are all in [space] and who attaches
     * no policy. For any other shape (an API key, a role cap, claims, roles in other spaces, attachments), build the
     * [Identity] and call [of]; a host that decides on the identity refuses one whose `memberships` is null.
     *
     * [validFor] is the credential's validity, not the header's: the header is refused a minute after [clock]'s now
     * (above). [clock] is there for tests of that age itself; the default is the system clock.
     *
     * @throws IllegalArgumentException as [of], or when [validFor] is not positive
     */
    @JvmStatic
    @JvmOverloads
    fun forSubject(
        subject: String,
        tenant: String = TenantId.DEFAULT.value,
        app: String = "",
        space: String = "",
        roles: Set<String> = emptySet(),
        validFor: Duration = DEFAULT_VALIDITY,
        clock: Clock = Clock.systemUTC(),
    ): String {
        require(validFor > Duration.ZERO) { "A credential is valid for a positive duration, not $validFor." }
        val now = clock.instant()
        val credential = Identity.Credential(
            method = Identity.Method.JWT,
            kind = Identity.Credential.Kind.PERSON,
            id = RandomIdGenerator.generate(),
            expiresAt = now.plus(validFor),
            issuedAt = now,
            session = TokenSession(RandomIdGenerator.generate(), now, 1),
        )
        return of(
            Identity(
                tenant = tenant,
                subject = subject,
                app = app,
                space = space,
                roles = roles,
                credential = credential,
                issuedAt = now,
                requestId = RandomIdGenerator.generate(),
                memberships = Identity.Memberships.NONE,
            )
        )
    }

    /**
     * What a gateway forwards for a request that carries [token], verified by [verifier] over [revocations] (name
     * [RevocationReader.NONE] for a test in which nothing is revoked), with the token claims [claims] names
     * (`cosec.gateway.identity.claims`): made as a gateway makes it, by CoSec's token
     * authenticator and identity issuer, so with CoSec's own token it carries the token's real session, which a tenant
     * switch behind the gateway issues from. As [PermittedRequest.withToken], only the gateway's rule is a stand-in: it
     * permits the request, and its scope is the token's tenant, with no app or space. The header is made now and is
     * refused a minute later (above).
     *
     * @throws IllegalArgumentException when [verifier] refuses [token] (invalid, expired, not valid yet, revoked), or
     * as [of]
     * @throws IllegalStateException when the gateway would forward nothing: a principal whose authenticator names no
     * credential, or an identity too large to forward
     */
    @JvmStatic
    @JvmOverloads
    fun withToken(
        token: String,
        verifier: TokenVerifier,
        revocations: RevocationReader,
        claims: Set<String> = emptySet(),
    ): String {
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf("Bearer $token"))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "127.0.0.1",
            host = HostScope(null),
            // A routed request: a gateway forwards the identity only to a route that receives it (RC1-49).
            requestAttributes = mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "true"),
        )
        val outcome = PermittingHost.pipeline(TokenAuthenticator(verifier, revocations)) {
            forwardIdentity { this.claims = claims }
        }.use { runBlocking { it.evaluate(facts) } }
        check(outcome.status != INTERNAL_ERROR) {
            "The gateway would fail the request (${outcome.problem?.type?.name}): see the log, such as an identity too " +
                "large to forward."
        }
        require(outcome.security?.principal != null) {
            "The verifier refused the token: invalid, expired, not valid yet or revoked."
        }
        val header = checkNotNull(outcome.forward[NAME]) {
            "The gateway would forward no identity for this token: ${outcome.status}."
        }
        return readBack(header)
    }

    /** [header] as a service reads it, refused as there. */
    private fun readBack(header: String): String {
        try {
            IdentityCodec.decode(header)
        } catch (e: IdentityFormatException) {
            throw IllegalArgumentException("A service would refuse the identity: ${e.message}", e)
        }
        return header
    }
}
