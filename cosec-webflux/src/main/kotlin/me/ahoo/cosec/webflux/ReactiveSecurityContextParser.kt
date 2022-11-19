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

import me.ahoo.cosec.context.AbstractSecurityContextParser
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.jwt.Jwts.parseAccessToken
import me.ahoo.cosec.principal.CoSecPrincipal
import me.ahoo.cosec.token.AccessToken
import me.ahoo.cosec.token.TokenConverter
import org.springframework.web.server.ServerWebExchange

/**
 * Jwt Security Context Parser .
 *
 * @author ahoo wang
 */
class ReactiveSecurityContextParser(
    private val tokenConverter: TokenConverter
) : AbstractSecurityContextParser<ServerWebExchange>() {
    override fun getAccessToken(request: ServerWebExchange): AccessToken? {
        val authorization = request.request.headers.getFirst(Jwts.AUTHORIZATION_KEY)
        return parseAccessToken(authorization)
    }

    override fun asPrincipal(accessToken: AccessToken): CoSecPrincipal = tokenConverter.asPrincipal(accessToken)
}
