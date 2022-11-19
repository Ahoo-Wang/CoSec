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
package me.ahoo.cosec.servlet

import me.ahoo.cosec.context.AbstractSecurityContextParser
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.jwt.Jwts.asPrincipal
import me.ahoo.cosec.jwt.Jwts.parseAccessToken
import me.ahoo.cosec.principal.CoSecPrincipal
import me.ahoo.cosec.token.AccessToken
import javax.servlet.http.HttpServletRequest

/**
 * Inject Security Context Parser .
 * WARNING: Without verify!!!
 *
 * @author ahoo wang
 */
object InjectSecurityContextParser :
    AbstractSecurityContextParser<HttpServletRequest>() {
    override fun getAccessToken(request: HttpServletRequest): AccessToken? {
        val authorization = request.getHeader(Jwts.AUTHORIZATION_KEY)
        return parseAccessToken(authorization)
    }

    override fun asPrincipal(accessToken: AccessToken): CoSecPrincipal = asPrincipal(accessToken.accessToken)
}
