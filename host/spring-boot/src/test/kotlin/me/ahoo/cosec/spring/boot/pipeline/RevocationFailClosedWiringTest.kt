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

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

/**
 * PIP-7: whether a token is accepted while its revocations cannot be read is the operator's choice, which the starter
 * hands to the authenticator it builds as `TokenAuthenticator.acceptUnavailableRevocations`, a parameter with no default:
 * accepted unless `cosec.authentication.revocation.fail-closed` is set, refused (503 in the pipeline) when it is.
 */
class RevocationFailClosedWiringTest {
    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { Unreadable })
        .withBean(TokenVerifier::class.java, { Verifier })

    private fun authenticate(authenticator: Authenticator): AuthnResult =
        runBlocking { authenticator.authenticate(Credentials(TOKEN, null)) }

    @Test
    fun `by default a token is accepted while its revocations cannot be read - PIP-7`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val result = authenticate(context.getBean(Authenticator::class.java))
            (result as AuthnResult.Authenticated).principal.id.assert().isEqualTo(PrincipalId("alice"))
        }
    }

    @Test
    fun `with fail-closed a token is refused while its revocations cannot be read - PIP-7`() {
        runner.withPropertyValues("cosec.authentication.revocation.fail-closed=true").run { context ->
            context.startupFailure.assert().isNull()
            assertThrows<RevocationUnavailableException> { authenticate(context.getBean(Authenticator::class.java)) }
        }
    }

    private object Verifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken {
            check(accessToken == TOKEN) { "not the test's token" }
            return VerifiedToken("t1", "alice", "acme", null, Identity.Credential.Kind.PERSON)
        }

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private object Unreadable : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = throw RevocationUnavailableException("down")

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            throw RevocationUnavailableException("down")
    }

    private companion object {
        const val TOKEN = "t"
    }
}
