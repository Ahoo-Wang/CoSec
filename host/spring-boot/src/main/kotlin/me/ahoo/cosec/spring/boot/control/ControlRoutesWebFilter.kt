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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpResponse
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.pipeline.ReactiveClientIp
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.getSecurityContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.io.InputStream
import java.net.URLDecoder
import org.springframework.web.reactive.function.server.RequestPredicates as ReactivePredicates
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions as ReactiveRouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse
import org.springframework.web.servlet.function.RequestPredicates as ServletPredicates
import org.springframework.web.servlet.function.RouterFunction as ServletRouterFunction
import org.springframework.web.servlet.function.RouterFunctions as ServletRouterFunctions
import org.springframework.web.servlet.function.ServerResponse as ServletServerResponse

/** [delegate] for the requests under [basePath] only; every other request goes on untouched. */
internal class ControlRoutesWebFilter(
    private val delegate: PipelineWebFilter,
    private val basePath: String
) : WebFilter, Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        return if (path == basePath || path.startsWith("$basePath/")) {
            delegate.filter(
                exchange,
                chain
            )
        } else {
            chain.filter(exchange)
        }
    }

    override fun getOrder(): Int = delegate.order
}
