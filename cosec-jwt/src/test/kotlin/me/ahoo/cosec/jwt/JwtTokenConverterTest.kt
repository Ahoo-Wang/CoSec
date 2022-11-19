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
package me.ahoo.cosec.jwt

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.principal.CoSecPrincipal
import me.ahoo.cosec.principal.TenantPrincipal
import me.ahoo.cosec.token.CompositeToken
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.token.TokenPrincipal
import me.ahoo.cosec.token.TokenTenantPrincipal
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * @author ahoo wang
 */
internal class JwtTokenConverterTest {
    var algorithm = Algorithm.HMAC256("FyN0Igd80Gas8stTavArGKOYnS9uLWGA_")
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, algorithm)

    @Test
    fun asToken() {
        val token: CompositeToken = jwtTokenConverter.asToken(TenantPrincipal.ANONYMOUS)
        assertThat(token, notNullValue())
    }

    @Test
    fun asPrincipal() {
        val token: CompositeToken = jwtTokenConverter.asToken(TenantPrincipal.ANONYMOUS)
        val principal: TokenTenantPrincipal = jwtTokenConverter.asPrincipal(token)
        assertThat(principal.name, equalTo(CoSecPrincipal.ANONYMOUS_NAME))
    }

    @Test
    fun refresh() {
        val oldToken: CompositeToken = jwtTokenConverter.asToken(TenantPrincipal.ANONYMOUS)
        val newTokenPrincipal = jwtTokenConverter.refresh<TokenTenantPrincipal>(oldToken)
        assertThat(newTokenPrincipal.id, equalTo(TenantPrincipal.ANONYMOUS.id))
        assertThat(newTokenPrincipal.tenant.tenantId, equalTo(TenantPrincipal.ANONYMOUS.tenant.tenantId))
    }

    @Test
    fun refreshWhenExpired() {
        val converter =
            JwtTokenConverter(MockIdGenerator.INSTANCE, algorithm, Duration.ofMillis(1), Duration.ofMillis(1))
        val oldToken: CompositeToken = converter.asToken(TenantPrincipal.ANONYMOUS)
        TimeUnit.SECONDS.sleep(1)
        Assertions.assertThrows(TokenExpiredException::class.java) { converter.refresh<TokenPrincipal>(oldToken) }
    }
}
