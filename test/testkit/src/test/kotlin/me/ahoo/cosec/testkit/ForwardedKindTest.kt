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

package me.ahoo.cosec.testkit

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * `IDN-46`, end to end below the system test: what a gateway forwards as `auth.kind` for a token CoSec's login issued, for
 * the pair a refresh issued from it, and for a token that does not say whose it is, each verified by CoSec's own verifier
 * and forwarded by its identity issuer.
 */
class ForwardedKindTest {
    private val secret = "forwarded-kind-test-secret"
    private val signing = JwtSigning.hmac256(secret)
    private val ids = AtomicLong()
    private val converter = JwtTokenConverter(IdGenerator { "t-${ids.incrementAndGet()}" }, signing)
    private val verifier = JwtTokenVerifier(signing)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())

    private fun forwardedKind(accessToken: String): Kind? =
        IdentityCodec.decode(IdentityHeader.withToken(accessToken, verifier, RevocationReader.NONE)).credential?.kind

    private fun signIn(kind: Kind) = converter.toToken(
        TokenSubject("alice", kind, tenant = "acme")
            .inSession(runBlocking { families.start(TenantId("acme"), PrincipalId("alice")) })
    )

    @Test
    fun `a gateway forwards the kind CoSec's login declared, through a refresh, and a token without one as a workload's - IDN-46`() {
        val refresh =
            RefreshTokenAuthentication(
                verifier,
                TokenRevocation(RevocationReader.NONE),
                families,
                MembershipRefresh.CLAIMS
            )
        Kind.entries.forEach { kind ->
            val pair = signIn(kind)
            forwardedKind(pair.accessToken).assert().isEqualTo(kind)
            val refreshed = converter.toToken(
                runBlocking {
                    refresh.authenticate(object : RefreshTokenCredentials {
                        override val accessToken: String = pair.accessToken
                        override val refreshToken: String = pair.refreshToken
                    })
                }
            )
            forwardedKind(refreshed.accessToken).assert().isEqualTo(kind)
        }
        // A token signed with CoSec's key but without the claim, as an earlier 8.0 preview issued it.
        val withoutKind = JWT.create()
            .withJWTId("preview")
            .withSubject("alice")
            .withClaim("tenantId", "acme")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(60)))
            .sign(Algorithm.HMAC256(secret))
        forwardedKind(withoutKind).assert().isEqualTo(Kind.WORKLOAD)
    }
}
