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
package me.ahoo.cosec.context

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.token.PrincipalConverter
import me.ahoo.cosec.token.SimpleAccessToken

/**
 * Default Security Context Parser .
 *
 * @author ahoo wang
 */
open class DefaultSecurityContextParser(private val principalConverter: PrincipalConverter) : SecurityContextParser {
    override fun parse(request: Request): SecurityContext {
        val authorization = request.getHeader(AUTHORIZATION_HEADER_KEY)
        if (authorization.isBlank()) {
            return SimpleSecurityContext.anonymous()
        }
        val accessToken = SimpleAccessToken(authorization)
        val principal = principalConverter.toPrincipal(accessToken)
        return SimpleSecurityContext(principal)
    }
}
