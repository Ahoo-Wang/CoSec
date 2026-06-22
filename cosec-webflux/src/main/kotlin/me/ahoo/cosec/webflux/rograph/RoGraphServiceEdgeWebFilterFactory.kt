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

package me.ahoo.cosec.webflux.rograph

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import org.springframework.web.server.ServerWebExchange

class RoGraphServiceEdgeWebFilterFactory(
    private val securityContextParser: SecurityContextParser,
    private val authorization: Authorization,
    private val requestAttributesAppenders: List<RequestAttributesAppender> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver
) {
    fun authorizationFilter(): ReactiveAuthorizationFilter =
        ReactiveAuthorizationFilter(
            securityContextParser = securityContextParser,
            requestParser = ReactiveRequestParser(
                remoteIpResolver = remoteIpResolver,
                requestAttributesAppends = requestAttributesAppenders,
            ),
            authorization = authorization,
        )
}
