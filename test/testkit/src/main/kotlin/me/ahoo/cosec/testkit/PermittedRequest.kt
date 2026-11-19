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
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier

/**
 * The [SecurityContext] of a request a token would be permitted on, for testing what an application does with a
 * context (a tenant switch, a handler that reads it); not a way to decide anything, for which [PipelineFixture] and
 * [PolicyTestKit] exist.
 *
 * The context is made as in production: CoSec's own token authenticator verifies the token on a request that carries it
 * in `Authorization`, and reads its [revocations], so what the context carries by provenance (the principal, its tenant
 * and app, and with CoSec's own [TokenVerifier] the token's session, which a tenant switch issues from) is what the
 * token says. Only the host's rule is a stand-in: it permits the request, whatever the documents would say.
 *
 * The request is the pipeline's, so a test can give it the headers a client would send, and what the pipeline reads
 * from them is what the context answers: a `CoSec-Device-Id` is the context's `deviceId`, and two different values are
 * as ambiguous here as on a host.
 */
@ExperimentalCoSecApi
object PermittedRequest {
    /**
     * The context of the request [token] makes, verified by [verifier] over [revocations]: name
     * [RevocationReader.NONE] for a test in which nothing is revoked.
     *
     * [headers] are the request's other headers, such as `CoSec-Device-Id`; names are case-insensitive. The token is the
     * request's credential and no header can replace it: `Authorization`, `X-API-Key` and `CoSec-Identity` are refused.
     *
     * @throws IllegalArgumentException when [verifier] refuses [token] (invalid, expired, revoked), so a test never
     * goes on with an anonymous context it did not ask for, or when [headers] names a credential header
     * @throws IllegalStateException when the pipeline does not permit the request: a header it refuses, such as a
     * `CoSec-Device-Id` sent with two different values (`400`), as it would on a host
     */
    @JvmStatic
    @JvmOverloads
    fun withToken(
        token: String,
        verifier: TokenVerifier,
        revocations: RevocationReader,
        headers: Map<String, List<String>> = emptyMap(),
    ): SecurityContext {
        headers.keys.firstOrNull { name -> CREDENTIAL_HEADERS.any { it.equals(name, ignoreCase = true) } }?.let {
            throw IllegalArgumentException(
                "The header [$it] carries a credential, and the credential of this request is the token: " +
                    "pass the token as [token], not through [headers]."
            )
        }
        val pipeline = PermittingHost.pipeline(TokenAuthenticator(verifier, revocations))
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/",
            rawQuery = null,
            headers = Headers.of(headers + ("Authorization" to listOf("Bearer $token"))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "127.0.0.1",
            host = HostScope(null),
        )
        val outcome = runBlocking { pipeline.evaluate(facts) }
        val security = checkNotNull(outcome.security) { "The request was not permitted: ${outcome.status}." }
        val context = security.securityContext
        require(context.principal.authenticated) { "The verifier refused the token: invalid, expired or revoked." }
        return context
    }

    /**
     * The [SecurityContext] of a request [subject] of [tenant] makes on a host that decides with policies
     * (`cosec.pipeline.mode=enforce`), in [space] of [app] (the default space when [space] is empty), with [roles]
     * effective there; with no token and no documents. For testing what an application does with a signed-in caller's
     * context (a handler, a function that reads it).
     *
     * It is the context [PipelineFixture.securityContext] answers for the same principal: made by the pipeline, through
     * the kit's authenticator, on a host whose rule permits the request. It carries no credential: the decision event's
     * `subject.method` is `testkit`, there is no session (so no tenant switch issues from it; use [withToken] with a token
     * CoSec issued for that), and [SecurityContext.forwardedIdentity] is null, as on an enforce-mode host. [headers] are the
     * request's other headers, as for [withToken]; credential headers are refused.
     *
     * @throws IllegalArgumentException when [headers] names a credential header, or the pipeline refuses the request: an
     * id it does not accept, or a [space] in which [roles] holds none
     */
    @JvmStatic
    @JvmOverloads
    fun forSubject(
        subject: String,
        tenant: String = TenantId.DEFAULT.value,
        app: String = "",
        space: String = "",
        roles: Set<String> = emptySet(),
        headers: Map<String, List<String>> = emptyMap(),
    ): SecurityContext {
        refuseCredentials(headers)
        val principal = PipelineFixture.Principal(
            id = subject,
            tenant = tenant,
            roles = if (space.isEmpty()) roles else roles.mapTo(LinkedHashSet()) { "$it@$space" },
            app = app.ifEmpty { null },
        )
        val pipeline = PermittingHost.pipeline(PipelineFixture.fixtureAuthenticator(mapOf(SUBJECT_TOKEN to principal)))
        val scoped = if (space.isEmpty()) headers else headers + (CoSecParameters.SPACE_ID to listOf(space))
        val outcome = runBlocking {
            pipeline.evaluate(facts(scoped + ("Authorization" to listOf("Bearer $SUBJECT_TOKEN")), app))
        }
        val security = requireNotNull(outcome.security) {
            "The pipeline refused the request of [$subject] (${outcome.status} ${outcome.problem?.type?.name}): " +
                "a space needs a role there, and every id must be one CoSec accepts."
        }
        return security.securityContext
    }

    /**
     * The [SecurityContext] a service behind a CoSec gateway (`cosec.pipeline.mode=gateway`) reads for a request
     * carrying the `CoSec-Identity` [header], made by the identity gate ([Pipeline.behindGateway]): what [IdentityHeader]
     * writes, with any of its forms ([IdentityHeader.forSubject], [IdentityHeader.withToken], [IdentityHeader.of]).
     *
     * [SecurityContext.forwardedIdentity] is the forwarded identity there, non-null, as on such a service; the principal, scope
     * and credential are what the header says. [headers] are the request's other headers; credential headers are
     * refused, the identity being the request's credential.
     *
     * @throws IllegalArgumentException when [headers] names a credential header, or the gate refuses the header (expired,
     * too old, malformed), naming the status and the problem
     */
    @JvmStatic
    @JvmOverloads
    fun withIdentity(header: String, headers: Map<String, List<String>> = emptyMap()): SecurityContext {
        refuseCredentials(headers)
        val outcome = runBlocking {
            Pipeline.behindGateway().evaluate(facts(headers + (IdentityHeader.NAME to listOf(header)), null))
        }
        val security = requireNotNull(outcome.security) {
            "The gate refused the identity header (${outcome.status} ${outcome.problem?.type?.name}): make it per request."
        }
        return security.securityContext
    }

    private fun refuseCredentials(headers: Map<String, List<String>>) {
        headers.keys.firstOrNull { name -> CREDENTIAL_HEADERS.any { it.equals(name, ignoreCase = true) } }?.let {
            throw IllegalArgumentException(
                "The header [$it] carries a credential, and this request's credential is the kit's: " +
                    "pass the subject or the identity as such, not through [headers]."
            )
        }
    }

    private fun facts(headers: Map<String, List<String>>, app: String?) = HttpFacts(
        method = "POST",
        rawPath = "/",
        rawQuery = null,
        headers = Headers.of(headers),
        cookies = Cookies.of(emptyMap()),
        remoteAddress = "127.0.0.1",
        host = HostScope(app?.ifEmpty { null }?.let(::AppId)),
    )

    /** The token the kit's authenticator knows the [forSubject] principal by, within that one request. */
    private const val SUBJECT_TOKEN = "testkit-subject"

    /**
     * Ends the request of [context], as a host does when its response is complete (`PIP-26`): from then on every check
     * of it is denied. For a test of code that keeps a context and checks with it after the request. The context
     * [withToken] answers is not ended until this is called. Idempotent.
     *
     * @throws IllegalArgumentException when [context] is not one the pipeline made
     */
    @JvmStatic
    fun end(context: SecurityContext) {
        requireNotNull(RequestSecurity.of(context)) { "The context [$context] is not one the pipeline made." }.end()
    }

    /** The headers that carry who the caller is: a test passes the token, never one of these. */
    private val CREDENTIAL_HEADERS = listOf("Authorization", "X-API-Key", "CoSec-Identity")
}
