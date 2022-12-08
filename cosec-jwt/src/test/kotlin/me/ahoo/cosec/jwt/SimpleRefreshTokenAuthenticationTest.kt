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

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.authentication.token.RefreshTokenCredentials
import me.ahoo.cosec.authentication.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.context.tenant
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SimpleRefreshTokenAuthenticationTest {
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, JwtFixture.ALGORITHM)
    private var jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun authenticate() {
        val refreshTokenAuthentication = SimpleRefreshTokenAuthentication(jwtTokenVerifier)
        assertThat(refreshTokenAuthentication.supportCredentials, `is`(RefreshTokenCredentials::class.java))
        val oldToken: CompositeToken = jwtTokenConverter.asToken(SimpleTenantPrincipal.ANONYMOUS)

        refreshTokenAuthentication.authenticate(object : RefreshTokenCredentials {
            override val accessToken: String
                get() = oldToken.accessToken
            override val refreshToken: String
                get() = oldToken.refreshToken
        }).test()
            .consumeNextWith {
                assertThat(it.id, equalTo(SimpleTenantPrincipal.ANONYMOUS.id))
                assertThat(
                    it.tenant.tenantId,
                    equalTo(SimpleTenantPrincipal.ANONYMOUS.tenant.tenantId)
                )
            }.verifyComplete()
    }
}
