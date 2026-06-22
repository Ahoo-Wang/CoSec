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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgeWebFilterFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RoGraphServiceEdgeWebFilterFactoryTest {

    @Test
    fun createsAuthorizationFilterFromReusableCoSecExtensionPoints() {
        var authorizedRequest: Request? = null
        val factory = RoGraphServiceEdgeWebFilterFactory(
            securityContextParser = SecurityContextParser { request ->
                request.attributes.getValue(SYSTEM_ID_ATTRIBUTE).assert().isEqualTo(SYSTEM_ID)
                SimpleSecurityContext(SimplePrincipal(SUBJECT_ID))
            },
            requestAttributesAppenders = listOf(
                object : RequestAttributesAppender {
                    override fun append(request: Request): Request =
                        request.mergeAttributes(mapOf(SYSTEM_ID_ATTRIBUTE to SYSTEM_ID))
                },
            ),
            authorization = { request, context ->
                context.principal.id.assert().isEqualTo(SUBJECT_ID)
                authorizedRequest = request
                AuthorizeResult.ALLOW.toMono()
            },
        )
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/rograph/systems/$SYSTEM_ID")
                .header(RequestIdCapable.REQUEST_ID_KEY, REQUEST_ID)
                .build(),
        )
        var chainCalled = false
        val chain = WebFilterChain {
            chainCalled = true
            Mono.empty()
        }

        factory.authorizationFilter().filter(exchange, chain).block()

        chainCalled.assert().isTrue()
        val request = checkNotNull(authorizedRequest)
        request.requestId.assert().isEqualTo(REQUEST_ID)
        request.attributes.getValue(SYSTEM_ID_ATTRIBUTE).assert().isEqualTo(SYSTEM_ID)
    }

    private companion object {
        const val REQUEST_ID = "req-rograph-production-adapter"
        const val SUBJECT_ID = "employee-001"
        const val SYSTEM_ID = "sales-lead-followup"
        const val SYSTEM_ID_ATTRIBUTE = "rograph.systemId"
    }
}
