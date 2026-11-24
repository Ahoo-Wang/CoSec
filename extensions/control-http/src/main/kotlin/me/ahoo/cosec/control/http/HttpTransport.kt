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

package me.ahoo.cosec.control.http

import kotlinx.coroutines.suspendCancellableCoroutine
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.RoutedControlService
import me.ahoo.cosec.control.sdk.ServiceCall
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.identity.RequestEndedException
import me.ahoo.cosec.runtime.identity.ServiceIdentity
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How [HttpControlService] carries a call: one request to the service port, its caller in `CoSec-Identity`. */
internal class HttpTransport(
    baseUri: URI,
    private val client: HttpClient,
    private val timeout: Duration,
    private val app: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: IdGenerator = RandomIdGenerator,
    maxAnswerBytes: Long = HttpControlService.MAX_ANSWER_BYTES,
) : RoutedControlService() {
    private val base = baseUri.toString().trimEnd('/')

    /**
     * The gateway's one mapping of a request's security to the header it forwards, with every attribute of the caller
     * (DR1-F-1): the host's policies may read any of them, as the in-process facade's do.
     */
    private val issuer = IdentityIssuer(clock = clock, relay = true)

    private val answer = BoundedAnswer(maxAnswerBytes)

    private val ended = RateLimitedWarning(clock)

    init {
        require(baseUri.isAbsolute && baseUri.scheme in SCHEMES) { "The control API's base is an http or https URI." }
        require(baseUri.rawQuery == null && baseUri.rawFragment == null) { "The control API's base has no query." }
        require(timeout > Duration.ZERO) { "A call's timeout is positive, not $timeout." }
        // A redirect is a refusal (CTL-77), and a client that follows one sends CoSec-Identity, which the host believes
        // without a signature, to wherever the redirect points (DR1-F-17).
        require(client.followRedirects() == HttpClient.Redirect.NEVER) { FOLLOWS_REDIRECTS }
    }

    override suspend fun call(caller: ControlCaller, call: ServiceCall): String {
        val request = HttpRequest.newBuilder(uri(call))
            .timeout(timeout)
            .header(ACCEPT, MEDIA_TYPES)
            .apply { stated(caller, call)?.let { header(IdentityHeader.NAME, it) } }
            .apply {
                val body = call.body
                if (body == null) {
                    method(call.method, HttpRequest.BodyPublishers.noBody())
                } else {
                    header(CONTENT_TYPE, JSON).method(call.method, HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()
        val response = client.sendAsync(request, answer).awaitOrAbort()
        // Only a 2xx is the route's answer: a redirect the client did not follow, or a proxy's error page, is not.
        if (response.statusCode() !in SUCCESS) throw ControlRefusedException(response.statusCode(), response.body())
        return response.body()
    }

    /**
     * [identity], or the refusal the in-process facade answers a request that has ended (`PIP-26`, `CTL-77`, DR1-F-2):
     * `403` without detail, before anything is sent. The host never sees it, so it is in no host's audit: warned of here,
     * at most once a minute, naming the principal, its tenant and the route, never its token.
     */
    private fun stated(caller: ControlCaller, call: ServiceCall): String? = try {
        identity(caller)
    } catch (e: RequestEndedException) {
        val security = caller.context?.let(RequestSecurity::of)
        // Both ids are opaque text a token or an authenticator chose: printed escaped, they cannot forge a line (RC1-15).
        val principal = Identifiers.printable(security?.principal?.id?.value ?: "anonymous")
        ended.warn {
            "A control call $call was made for request [${Identifiers.printable(e.requestId)}] of principal " +
                "[$principal] of tenant [${security?.scope?.tenant?.value}] after it ended: refused, not sent, so no " +
                "host records it (PIP-26)"
        }
        throw ControlRefusedException(ProblemType.FORBIDDEN.status, forbidden(e.requestId)).apply { initCause(e) }
    }

    /**
     * The header that states [caller]: the gateway's own for a request's caller (none for an anonymous one, which the
     * host refuses as anonymous), or the service identity the in-process implementation admits. A signed-in principal
     * whose authenticator named no credential cannot be stated over the network: refused here, rather than sent as
     * anonymous.
     */
    private fun identity(caller: ControlCaller): String? {
        caller.context?.let { context ->
            val security = requireNotNull(RequestSecurity.of(context)) { ControlCaller.NOT_COSEC_CONTEXT }
            require(security.principal == null || security.principal?.credential != null) { NO_CREDENTIAL }
            return issuer.issue(security)
        }
        val identity = ServiceIdentity.of(
            checkNotNull(caller.principal),
            caller.roles,
            app,
            clock.instant(),
            ids.generate()
        )
        return IdentityHeader.write(identity)
    }

    /**
     * The exchange's answer, and a caller that goes away takes the exchange with it (`CTL-82`): `cancel(true)` is what the
     * JDK's client reads as "abort the exchange" (it closes an HTTP/1.1 connection, resets an HTTP/2 stream), and the host
     * sees its request end and cancels its work. kotlinx's `await` cancels with `false`, which leaves the request running
     * on the host until the timeout ends it.
     */
    private suspend fun <T> CompletableFuture<T>.awaitOrAbort(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            if (failure == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException((failure as? CompletionException)?.cause ?: failure)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

    private fun uri(call: ServiceCall): URI {
        val query = call.query.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return URI.create(base + call.path + if (query.isEmpty()) "" else "?$query")
    }

    private companion object {
        val SUCCESS = 200..299
        const val ACCEPT = "Accept"
        const val CONTENT_TYPE = "Content-Type"
        const val JSON = "application/json"
        const val MEDIA_TYPES = "application/json, application/problem+json"
        val SCHEMES = setOf("http", "https")
        const val FOLLOWS_REDIRECTS = "The control API's client follows redirects: a redirect is a refusal, and one " +
            "followed would send CoSec-Identity where it points. Build it with followRedirects(HttpClient.Redirect.NEVER)."
        const val NO_CREDENTIAL = "The request's principal has no credential, so a control-plane host cannot be told " +
            "who it is: call as the application's own principal (ControlCaller.service), or in process."

        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** The problem a host answers a refused call, member by member as the control API writes it, without detail. */
        fun forbidden(requestId: String): String = with(ProblemType.FORBIDDEN) {
            """{"type":${text(uri)},"title":${text(title)},"status":$status,"code":${text(name)},""" +
                """"requestId":${text(requestId)}}"""
        }

        /** [value] as a JSON string: quoted, with a quote, a backslash and a control character escaped. */
        private fun text(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when {
                    char == '"' || char == '\\' -> append('\\').append(char)
                    char < ' ' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
