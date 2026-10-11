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

import com.auth0.jwt.JWT
import me.ahoo.cosec.jwt.Jwts.TOKEN_PREFIX
import me.ahoo.cosec.jwt.Jwts.removeBearerPrefix
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.token.TokenVerificationException
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date

class JwtsTest {

    @Test
    fun removeBearerPrefixIfEmpty() {
        assertThat("".removeBearerPrefix(), equalTo(""))
    }

    @Test
    fun removeBearerPrefix() {
        assertThat("${TOKEN_PREFIX}token".removeBearerPrefix(), equalTo("token"))
    }

    private fun signToken(expiresAt: Date, notBefore: Date? = null): String {
        val tokenBuilder = JWT.create().withSubject("subject").withExpiresAt(expiresAt)
        if (notBefore != null) {
            tokenBuilder.withNotBefore(notBefore)
        }
        return tokenBuilder.sign(JwtFixture.ALGORITHM)
    }

    @Test
    fun verifyTimeClaimsWhenValid() {
        val token = signToken(Date(System.currentTimeMillis() + 60_000))
        Jwts.verifyTimeClaims(Jwts.decode(token))
    }

    @Test
    fun verifyTimeClaimsWhenExpired() {
        val token = signToken(Date(System.currentTimeMillis() - 60_000))
        assertThrows<TokenExpiredException> { Jwts.verifyTimeClaims(Jwts.decode(token)) }
    }

    @Test
    fun verifyTimeClaimsWhenNotBeforeInPast() {
        val token = signToken(
            expiresAt = Date(System.currentTimeMillis() + 60_000),
            notBefore = Date(System.currentTimeMillis() - 60_000),
        )
        Jwts.verifyTimeClaims(Jwts.decode(token))
    }

    @Test
    fun verifyTimeClaimsWhenNotBeforeInFuture() {
        val token = signToken(
            expiresAt = Date(System.currentTimeMillis() + 60_000),
            notBefore = Date(System.currentTimeMillis() + 60_000),
        )
        assertThrows<TokenVerificationException> { Jwts.verifyTimeClaims(Jwts.decode(token)) }
    }

    @Test
    fun verifyTimeClaimsWhenExpiresAtMissing() {
        val token = JWT.create().withSubject("subject").sign(JwtFixture.ALGORITHM)
        assertThrows<TokenVerificationException> { Jwts.verifyTimeClaims(Jwts.decode(token)) }
    }
}
