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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

/** `IDN-46`: CoSec's verifier reads whose a token is from `cosec.kind`, and a refresh keeps it. */
class TokenKindJwtTest {
    private val verifier = JwtTokenVerifier(JwtFixture.SIGNING)
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.SIGNING)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())

    /** A token of alice whose `cosec.kind` [write] writes, or that has none. */
    private fun signed(write: (com.auth0.jwt.JWTCreator.Builder) -> Unit = {}): String {
        val builder = JWT.create()
            .withJWTId("jti")
            .withSubject("alice")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(60)))
        write(builder)
        return builder.sign(JwtFixture.ALGORITHM)
    }

    @Test
    fun `a token is a person's only when cosec kind says exactly person, and a workload's otherwise - IDN-46`() {
        verifier.verify(signed { it.withClaim(Jwts.KIND_CLAIM, "person") }).kind.assert().isEqualTo(Kind.PERSON)
        verifier.verify(signed { it.withClaim(Jwts.KIND_CLAIM, "workload") }).kind.assert().isEqualTo(Kind.WORKLOAD)
        // A token an earlier preview issued, and values this version does not know: the restrictive reading, no refusal.
        verifier.verify(signed()).kind.assert().isEqualTo(Kind.WORKLOAD)
        listOf<(com.auth0.jwt.JWTCreator.Builder) -> Unit>(
            { it.withClaim(Jwts.KIND_CLAIM, "Person") },
            { it.withClaim(Jwts.KIND_CLAIM, "robot") },
            { it.withClaim(Jwts.KIND_CLAIM, "") },
            { it.withClaim(Jwts.KIND_CLAIM, 1) },
            { it.withClaim(Jwts.KIND_CLAIM, true) },
            { it.withClaim(Jwts.KIND_CLAIM, listOf("person")) },
            { it.withNullClaim(Jwts.KIND_CLAIM) },
        ).forEach { verifier.verify(signed(it)).kind.assert().isEqualTo(Kind.WORKLOAD) }
        // What CoSec's issuer writes reads back as it was declared.
        Kind.entries.forEach { kind ->
            verifier.verify(converter.toToken(TokenSubject("alice", kind)).accessToken).kind.assert().isEqualTo(kind)
        }
    }

    private fun issue(kind: Kind): CompositeToken = converter.toToken(
        TokenSubject("alice", kind, tenant = "acme", roles = setOf("clerk"))
            .inSession(runBlocking { families.start(TenantId("acme"), PrincipalId("alice")) })
    )

    private fun credentials(pair: CompositeToken) = object : RefreshTokenCredentials {
        override val accessToken: String = pair.accessToken
        override val refreshToken: String = pair.refreshToken
    }

    @Test
    fun `a refresh keeps the kind of the token it refreshes, whatever its hook answers - IDN-46`(): Unit = runBlocking {
        val hook = MembershipRefresh<RefreshTokenCredentials> { _, _ -> TenantMembership(roles = setOf("auditor")) }
        listOf(MembershipRefresh.CLAIMS, hook).forEach { memberships ->
            val refresh =
                RefreshTokenAuthentication(verifier, TokenRevocation(RevocationReader.NONE), families, memberships)
            Kind.entries.forEach { kind ->
                val subject = refresh.authenticate(credentials(issue(kind)))
                subject.kind.assert().isEqualTo(kind)
                verifier.verify(converter.toToken(subject).accessToken).kind.assert().isEqualTo(kind)
            }
        }
    }
}
