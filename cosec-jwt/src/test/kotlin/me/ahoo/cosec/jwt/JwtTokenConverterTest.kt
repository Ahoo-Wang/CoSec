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
import me.ahoo.test.asserts.assert
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
        token.assert().isNotNull()
    }

    @Test
    fun toToken() {
        val principal =
            SimplePrincipal(
                "id",
                setOf("policyId"),
                setOf("roleId"),
                mapOf(
                    "attr_string" to "attr_string_value",
                    "attr_int" to 1,
                    "attr_boolean" to true,
                    "attr_double" to 1.0,
                    "attr_long" to 1L,
                    "attr_list" to listOf("a", "b", "c"),
                ),
            )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify<TokenPrincipal>(token)
        verified.id.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.authenticated().assert().isEqualTo(true)
        verified.anonymous().assert().isEqualTo(false)
        verified.attributes["attr_string"].assert().isEqualTo("attr_string_value")
        verified.attributes["attr_int"].assert().isEqualTo(1)
        verified.attributes["attr_boolean"].assert().isEqualTo(true)
        verified.attributes["attr_double"].assert().isEqualTo(1.0)
        verified.attributes["attr_long"].assert().isEqualTo(1)
        verified.attributes["attr_list"].assert().isEqualTo(listOf("a", "b", "c"))
        val token2 = jwtTokenConverter.toToken(verified)
        val verified2 = jwtTokenVerifier.verify<TokenPrincipal>(token2)
        verified2.id.assert().isEqualTo(principal.id)
    }

    @Test
    fun toTokenIfMissing() {
        val principal = SimplePrincipal(
            "id"
        )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify<TokenPrincipal>(token)
        verified.id.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.authenticated().assert().isEqualTo(true)
        verified.anonymous().assert().isEqualTo(false)
        verified.attributes.assert().isEmpty()
        val token2 = jwtTokenConverter.toToken(verified)
        val verified2 = jwtTokenVerifier.verify<TokenPrincipal>(token2)
        verified2.id.assert().isEqualTo(principal.id)
    }
}
