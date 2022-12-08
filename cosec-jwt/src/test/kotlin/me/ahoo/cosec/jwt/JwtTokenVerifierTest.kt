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

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.api.token.TokenTenantPrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class JwtTokenVerifierTest {
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun verify() {
        val token: CompositeToken = jwtTokenConverter.asToken(SimpleTenantPrincipal.ANONYMOUS)
        val principal: TokenTenantPrincipal = jwtTokenVerifier.verify(token)
        assertThat(principal.name, equalTo(CoSecPrincipal.ANONYMOUS_NAME))
    }

    @Test
    fun refresh() {
        val oldToken: CompositeToken = jwtTokenConverter.asToken(SimpleTenantPrincipal.ANONYMOUS)
        val newTokenPrincipal = jwtTokenVerifier.refresh<TokenTenantPrincipal>(oldToken)
        assertThat(newTokenPrincipal.id, equalTo(SimpleTenantPrincipal.ANONYMOUS.id))
        assertThat(newTokenPrincipal.tenant.tenantId, equalTo(SimpleTenantPrincipal.ANONYMOUS.tenant.tenantId))
    }

    @Test
    fun refreshWhenExpired() {
        val converter =
            JwtTokenConverter(MockIdGenerator.INSTANCE, JwtFixture.ALGORITHM, Duration.ofMillis(1), Duration.ofMillis(1))
        val oldToken: CompositeToken = converter.asToken(SimpleTenantPrincipal.ANONYMOUS)
        TimeUnit.SECONDS.sleep(1)
        Assertions.assertThrows(TokenExpiredException::class.java) { jwtTokenVerifier.refresh<TokenPrincipal>(oldToken) }
    }
}
