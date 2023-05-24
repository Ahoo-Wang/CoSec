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

import com.auth0.jwt.algorithms.Algorithm
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import javax.servlet.http.HttpServletRequest

abstract class SecurityContextParserSpec {
    var algorithm = Algorithm.HMAC256("FyN0Igd80Gas8stTavArGKOYnS9uLWGA_")
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, algorithm)
    var jwtTokenVerifier = JwtTokenVerifier(algorithm)
    abstract fun createSecurityContextParser(): SecurityContextParser<HttpServletRequest>

    @Test
    fun parseNone() {
        val request = mockk<HttpServletRequest> {
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
        }
        val securityContext = createSecurityContextParser().parse(request)
        assertThat(securityContext.principal, equalTo(SimpleTenantPrincipal.ANONYMOUS))
    }

    @Test
    fun parse() {
        val principal = SimplePrincipal("id")
        val token = jwtTokenConverter.asToken(principal).accessToken
        val request = mockk<HttpServletRequest> {
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns Jwts.TOKEN_PREFIX + token
        }
        val securityContext = createSecurityContextParser().parse(request)
        assertThat(securityContext.principal.id, equalTo(principal.id))
        assertThat(securityContext.principal.name, equalTo(principal.name))
    }
}
