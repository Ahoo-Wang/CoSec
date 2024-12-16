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
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * @author ahoo wang
 */
internal class JwtTokenConverterTest {
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun anonymousToToken() {
        val token: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        assertThat(token, notNullValue())
    }

    @Test
    fun toToken() {
        val principal =
            SimplePrincipal(
                "id",
                setOf("policyId"),
                setOf("roleId"),
                mapOf(
                    "attr_string" to "attr_string_value"
                ),
            )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        assertThat(token, notNullValue())
        val verified = jwtTokenVerifier.verify<TokenPrincipal>(token)
        assertThat(verified.id, equalTo(principal.id))
        assertThat(verified.attributes["attr_string"], equalTo("attr_string_value"))
        val token2 = jwtTokenConverter.toToken(verified)
        assertThat(token2, notNullValue())
    }
}
