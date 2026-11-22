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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.route.LoginRouteAnswer
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * The served login routes on Spring MVC: `POST` of each path, under the pipeline's servlet filter like every route. The
 * body is read up to [LoginRouteAnswer.MAX_BODY_BYTES] and no further; a request no pipeline decided is answered without
 * being read.
 */
internal object ServletLoginRoutes {
    fun of(routes: Map<String, LoginRouteHandler>, bodies: ProblemBodies): RouterFunction<ServerResponse> = routes.entries
        .map { (path, handler) -> RouterFunctions.route(RequestPredicates.POST(path)) { answer(it, handler, bodies) } }
        .reduce(RouterFunction<ServerResponse>::and)

    private fun answer(request: ServerRequest, handler: LoginRouteHandler, bodies: ProblemBodies): ServerResponse {
        val servlet = request.servletRequest()
        val security = RequestSecurity.of(servlet.securityContextOrNull())
            ?: return write(LoginRouteAnswer.NOT_UNDER_COSEC)
        val body = servlet.inputStream.use { it.readNBytes(LoginRouteAnswer.MAX_BODY_BYTES + 1) }
        if (body.size > LoginRouteAnswer.MAX_BODY_BYTES) {
            return write(LoginRouteAnswer.tooLarge(security.requestId, bodies))
        }
        // A servlet thread is the caller's own: waiting on it blocks no event loop. The handler runs on the default
        // dispatcher, as the control API's servlet routes run theirs.
        val answer = runBlocking(Dispatchers.Default) {
            handler.answer(servlet.getHeader(HttpHeaders.CONTENT_TYPE), body, security.requestId)
        }
        return write(answer)
    }

    private fun write(answer: LoginRouteAnswer): ServerResponse {
        val response = ServerResponse.status(answer.status)
            .header(HttpHeaders.CACHE_CONTROL, LoginRouteAnswer.CACHE_CONTROL)
        val contentType = answer.contentType ?: return response.build()
        return response.contentType(MediaType.parseMediaType(contentType)).body(answer.body)
    }
}
