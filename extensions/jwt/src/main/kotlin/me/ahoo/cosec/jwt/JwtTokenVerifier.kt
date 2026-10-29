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
import me.ahoo.cosec.jwt.Jwts.removeBearerPrefix
import me.ahoo.cosec.login.token.RefreshTokenMismatchException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier

/**
 * JWT-based token verifier.
 *
 * This implementation uses the Auth0 JWT library to verify JWT tokens
 * and extract principal information from them.
 *
 * @param algorithm The algorithm used to verify JWT signatures
 * @see TokenVerifier
 * @see JwtTokenConverter
 */
class JwtTokenVerifier(
    private val algorithm: Algorithm
) : TokenVerifier {
    private val jwtVerifier: JWTVerifier = JWT.require(algorithm)
        .withClaimPresence("exp")
        .build()

    @Suppress("TooGenericExceptionCaught")
    private fun verify(accessToken: String): DecodedJWT {
        try {
            return jwtVerifier.verify(accessToken)
        } catch (expired: TokenExpiredException) {
            throw me.ahoo.cosec.spi.token
                .TokenExpiredException(expired.message ?: "The token has expired.", expired)
        } catch (exception: Exception) {
            throw TokenVerificationException(exception.message ?: "The token is invalid.", exception)
        }
    }

    override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T {
        val removedBearerAccessToken = accessToken.accessToken.removeBearerPrefix()
        val decodedAccessToken = verify(removedBearerAccessToken)
        return Jwts.toPrincipal(decodedAccessToken)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T : TokenPrincipal> refresh(token: CompositeToken): T {
        val decodedRefreshToken: DecodedJWT = verify(token.refreshToken)
        // The access token may have expired, so it is decoded here and only its signature checked. Decoding stays in
        // this method: only the verifying methods may decode (PIP-11).
        val decodedAccessToken = try {
            Jwts.decode(token.accessToken)
        } catch (exception: Exception) {
            throw TokenVerificationException(exception.message ?: "The access token is malformed.", exception)
        }
        verifyAccessTokenSignature(decodedAccessToken)
        if (decodedRefreshToken.subject != decodedAccessToken.id) {
            throw RefreshTokenMismatchException()
        }
        return Jwts.toPrincipal(decodedAccessToken)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun verifyAccessTokenSignature(decodedAccessToken: DecodedJWT) {
        try {
            algorithm.verify(decodedAccessToken)
        } catch (exception: Exception) {
            throw TokenVerificationException(exception.message ?: "The access token's signature is invalid.", exception)
        }
    }
}
