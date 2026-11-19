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

package me.ahoo.cosec.spring.boot.authentication.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.route.LoginRouteAnswer
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * The served login routes on WebFlux: `POST` of each path, under the pipeline's filter like every route. The body is read
 * up to [LoginRouteAnswer.MAX_BODY_BYTES] and no further; a request no pipeline decided is answered without being read.
 */
internal object ReactiveLoginRoutes {
    fun of(routes: Map<String, LoginRouteHandler>): RouterFunction<ServerResponse> = routes.entries
        .map { (path, handler) -> RouterFunctions.route(RequestPredicates.POST(path)) { answer(it, handler) } }
        .reduce(RouterFunction<ServerResponse>::and)

    private fun answer(request: ServerRequest, handler: LoginRouteHandler): Mono<ServerResponse> {
        val security = RequestSecurity.of(request.exchange().securityContextOrNull())
            ?: return write(LoginRouteAnswer.NOT_UNDER_COSEC)
        val contentType = request.headers().firstHeader(HttpHeaders.CONTENT_TYPE)
        return DataBufferUtils.join(request.exchange().request.body, LoginRouteAnswer.MAX_BODY_BYTES)
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .defaultIfEmpty(EMPTY)
            .flatMap { body -> mono(Dispatchers.Default) { handler.answer(contentType, body, security.requestId) } }
            .onErrorResume(DataBufferLimitException::class.java) {
                Mono.just(LoginRouteAnswer.tooLarge(security.requestId))
            }
            .flatMap(::write)
    }

    private fun write(answer: LoginRouteAnswer): Mono<ServerResponse> {
        val response = ServerResponse.status(answer.status)
            .header(HttpHeaders.CACHE_CONTROL, LoginRouteAnswer.CACHE_CONTROL)
        val contentType = answer.contentType ?: return response.build()
        return response.contentType(MediaType.parseMediaType(contentType)).bodyValue(answer.body)
    }

    private val EMPTY = ByteArray(0)
}
