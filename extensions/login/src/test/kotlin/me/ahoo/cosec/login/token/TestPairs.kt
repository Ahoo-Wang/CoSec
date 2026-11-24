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


@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Instant

/**
 * CoSec's verifier as a switch meets a token pair, for the tests of `cosec-login`, which has no signing key: [of] writes
 * the pair a request was made with (its access token is the request's credential id; its refresh token carries what the
 * access token says), and [refresh] reads it back as the verified access token, as `JwtTokenVerifier.refresh` would. A
 * refresh token that does not read, an empty one included, is refused as one that does not belong to its access token.
 */
object TestPairs : TokenVerifier {
    private const val SEPARATOR = "|"
    private const val NONE = "-"

    /** The pair [context]'s request was made with; one that verifies as nothing for a context the pipeline did not make. */
    fun of(context: SecurityContext): CompositeToken {
        val principal = RequestSecurity.of(context)?.principal ?: return CompositeToken("unmade", "")
        return of(
            accessToken = checkNotNull(principal.credential).id,
            principal = principal.id.value,
            tenant = principal.tenant.value,
            issuedAt = principal.issuedAt,
            session = principal.session,
        )
    }

    /** A pair whose access token is [accessToken], of [principal] in [tenant], issued at [issuedAt] in [session]. */
    fun of(
        accessToken: String,
        principal: String = "alice",
        tenant: String = "acme",
        issuedAt: Instant? = null,
        session: TokenSession? = null,
    ): CompositeToken = CompositeToken(
        accessToken,
        listOf(
            accessToken,
            principal,
            tenant,
            issuedAt?.toEpochMilli()?.toString() ?: NONE,
            session?.id ?: NONE,
            session?.authTime?.epochSecond?.toString() ?: NONE,
            session?.generation?.toString() ?: NONE,
        ).joinToString(SEPARATOR),
    )

    override fun verify(accessToken: String): VerifiedToken = error("a switch verifies the pair, never an access token alone")

    override fun refresh(token: CompositeToken): VerifiedToken {
        val parts = token.refreshToken.split(SEPARATOR)
        if (parts.size != 7 || parts[0] != token.accessToken) {
            throw TokenVerificationException("The refresh token does not belong to the access token.")
        }
        val session = parts[4].takeIf { it != NONE }?.let { TokenSession(it, Instant.ofEpochSecond(parts[5].toLong()), parts[6].toLong()) }
        return VerifiedToken(
            id = parts[0],
            principal = parts[1],
            tenant = parts[2],
            app = null,
            issuedAt = parts[3].takeIf { it != NONE }?.toLong()?.let(Instant::ofEpochMilli),
            session = session,
        )
    }
}
