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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.RoutedControlService
import me.ahoo.cosec.control.sdk.ServiceCall
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.identity.ServiceIdentity
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

/** How [HttpControlService] carries a call: one request to the service port, its caller in `CoSec-Identity`. */
internal class HttpTransport(
    baseUri: URI,
    private val client: HttpClient,
    private val timeout: Duration,
    private val app: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: IdGenerator = RandomIdGenerator,
) : RoutedControlService() {
    private val base = baseUri.toString().trimEnd('/')

    /** The gateway's one mapping of a request's security to the header it forwards. */
    private val issuer = IdentityIssuer(clock = clock)

    init {
        require(baseUri.isAbsolute && baseUri.scheme in SCHEMES) { "The control API's base is an http or https URI." }
        require(baseUri.rawQuery == null && baseUri.rawFragment == null) { "The control API's base has no query." }
        require(timeout > Duration.ZERO) { "A call's timeout is positive, not $timeout." }
    }

    override suspend fun call(caller: ControlCaller, call: ServiceCall): String {
        val request = HttpRequest.newBuilder(uri(call))
            .timeout(timeout)
            .header(ACCEPT, MEDIA_TYPES)
            .apply { identity(caller)?.let { header(IdentityHeader.NAME, it) } }
            .apply {
                val body = call.body
                if (body == null) {
                    method(call.method, HttpRequest.BodyPublishers.noBody())
                } else {
                    header(CONTENT_TYPE, JSON).method(call.method, HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        // Only a 2xx is the route's answer: a redirect the client did not follow, or a proxy's error page, is not.
        if (response.statusCode() !in SUCCESS) throw ControlRefusedException(response.statusCode(), response.body())
        return response.body()
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
        const val NO_CREDENTIAL = "The request's principal has no credential, so a control-plane host cannot be told " +
            "who it is: call as the application's own principal (ControlCaller.service), or in process."

        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    }
}
