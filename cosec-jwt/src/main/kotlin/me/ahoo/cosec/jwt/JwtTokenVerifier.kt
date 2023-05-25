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
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.token.TokenVerifier

class JwtTokenVerifier(algorithm: Algorithm) : TokenVerifier {
    private val jwtVerifier: JWTVerifier = JWT.require(algorithm).build()

    @Suppress("TooGenericExceptionCaught")
    private fun verify(accessToken: String): DecodedJWT {
        try {
            return jwtVerifier.verify(accessToken)
        } catch (tokenExpiredException: TokenExpiredException) {
            throw me.ahoo.cosec.token.TokenExpiredException(tokenExpiredException.message!!, tokenExpiredException)
        } catch (exception: Exception) {
            throw me.ahoo.cosec.token.TokenVerificationException(exception.message!!, exception)
        }
    }

    override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T {
        val decodedAccessToken = verify(accessToken.accessToken)
        return Jwts.asPrincipal(decodedAccessToken)
    }

    override fun <T : TokenPrincipal> refresh(token: CompositeToken): T {
        val decodedRefreshToken: DecodedJWT = verify(token.refreshToken)
        val decodedAccessToken = Jwts.decode(token.accessToken)
        require(decodedRefreshToken.subject == decodedAccessToken.id) { "Illegal refreshToken." }
        return Jwts.asPrincipal(decodedAccessToken)
    }
}
