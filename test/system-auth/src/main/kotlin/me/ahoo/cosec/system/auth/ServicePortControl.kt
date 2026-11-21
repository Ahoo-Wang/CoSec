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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.system.auth

import kotlinx.coroutines.reactor.awaitSingle
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.spi.identity.Identity
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the control plane answered a call of the IAM: its status and the JSON of its body. */
class ControlAnswer(val status: Int, val body: String)

/**
 * What the IAM asks of the control plane, in the shape of the calls of the control plane's SDK (`ControlService`): the
 * caller is stated by the implementation, the request is the JSON the route takes and the answer is the JSON it
 * answers. Here the implementation is a call over the network to the control-plane host's service port ([ServicePortControl]);
 * the IAM that embeds the control plane implements the same with the SDK in process, and the code that asks stays.
 */
interface IamControl {
    /** `GET /documents/{key}`. */
    suspend fun document(key: String): ControlAnswer

    /** `POST /documents/{key}:apply`: [request] is the route's body; a tenant's document, which may not exist yet. */
    suspend fun apply(key: String, request: String): ControlAnswer

    /** `POST /changes`: [request] is the route's body. */
    suspend fun propose(request: String): ControlAnswer

    /** `POST /changes/{id}:skip-shadow`. */
    suspend fun skipShadow(id: String, reason: String): ControlAnswer

    /** `POST /changes/{id}:publish`. */
    suspend fun publish(id: String): ControlAnswer
}

/**
 * The IAM's own principal, `svc-iam` of the platform tenant, calling the control-plane host's service port: the
 * `CoSec-Identity` header is CoSec's public writer ([IdentityHeader]), made for each call (the host refuses one older
 * than a minute). The principal is stated, not proven: the network is the boundary, and the platform document says what
 * `svc-iam` may do.
 */
@Component
@ConditionalOnProperty("system.control.mode", havingValue = "http", matchIfMissing = true)
class ServicePortControl(@Value("\${system.control.service.url}") private val service: String) : IamControl {
    private val client = WebClient.builder().baseUrl("$service/cosec/control/v1").build()

    override suspend fun document(key: String): ControlAnswer = call("GET", "/documents/{key}", null, key)

    override suspend fun apply(key: String, request: String): ControlAnswer =
        call("POST", "/documents/{key}:apply", request, key)

    override suspend fun propose(request: String): ControlAnswer = call("POST", "/changes", request)

    override suspend fun skipShadow(id: String, reason: String): ControlAnswer =
        call("POST", "/changes/{id}:skip-shadow", """{"reason":"$reason"}""", id)

    override suspend fun publish(id: String): ControlAnswer = call("POST", "/changes/{id}:publish", "{}", id)

    /** [path] is a template: its one variable, [variable], is encoded as a path segment, so no value names another route. */
    private suspend fun call(method: String, path: String, body: String?, variable: String? = null): ControlAnswer {
        val request = client.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path, *listOfNotNull(variable).toTypedArray())
            .header(IdentityHeader.NAME, identity())
            .accept(MediaType.APPLICATION_JSON)
        val spec = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return spec.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("")
                .map { ControlAnswer(response.statusCode().value(), it) }
        }.awaitSingle()
    }

    private fun identity(): String {
        val now = Instant.now()
        return IdentityHeader.write(
            Identity(
                tenant = PLATFORM,
                subject = SERVICE_PRINCIPAL,
                app = CONTROL_APP,
                space = "",
                roles = emptySet(),
                credential = Identity.Credential(
                    Identity.Method.API_KEY,
                    SERVICE_PRINCIPAL,
                    now.plus(Duration.ofMinutes(1))
                ),
                issuedAt = now,
                requestId = UUID.randomUUID().toString().replace("-", ""),
            ),
        )
    }

    private companion object {
        const val PLATFORM = "(platform)"
        const val SERVICE_PRINCIPAL = "svc-iam"
        const val CONTROL_APP = "cosec-control"
    }
}
