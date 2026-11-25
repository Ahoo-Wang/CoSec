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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * R2D-7: tokens are on when a `TokenVerifier` bean or a `JwtSigning` exists, so an application whose tokens another
 * issuer signs needs no signing secret it never uses, and one with only the secret still verifies with CoSec's own
 * verifier. With neither, a request with a token is refused. A verifier alone issues nothing: no signing key, no token
 * converter.
 */
class TokensOnTest {
    private class OwnVerifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken =
            if (accessToken == VALID) {
                VerifiedToken(
                    "jti-1",
                    "alice",
                    null,
                    null
                )
            } else {
                throw TokenVerificationException("not a token of the issuer")
            }

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private val signing = JwtSigning.hmac256("0123456789abcdef0123456789abcdef")

    // JWT's own configuration is off, so that the beans below are the whole of what is declared.
    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPropertyCheckAutoConfiguration::class.java,
                CoSecAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("cosec.jwt.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(SnapshotDocumentStore::class.java, { InMemorySnapshotDocumentStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    private fun authenticate(context: ApplicationContext, token: String?): AuthnResult =
        runBlocking { context.getBean(Authenticator::class.java).authenticate(Credentials(token, null)) }

    private fun signed(): String =
        JwtTokenConverter(
            RandomIdGenerator,
            signing
        ).toToken(TokenSubject("alice", Identity.Credential.Kind.PERSON)).accessToken

    @Test
    fun `a verifier alone turns tokens on and issues none`() {
        runner.withBean(TokenVerifier::class.java, { OwnVerifier() }).run { context ->
            context.startupFailure.assert().isNull()
            (authenticate(context, VALID) as AuthnResult.Authenticated).principal.id.assert()
                .isEqualTo(PrincipalId("alice"))
            authenticate(context, "forged").assert().isInstanceOf(AuthnResult.Rejected::class.java)
            authenticate(context, null).assert().isEqualTo(AuthnResult.Anonymous)
            context.getBeansOfType(JwtSigning::class.java).assert().isEmpty()
            context.getBeansOfType(TokenConverter::class.java).assert().isEmpty()
            StartupSummary.report(context, context.environment).line.assert().contains("jwt=on (bean OwnVerifier)")
        }
    }

    @Test
    fun `a signing key alone turns tokens on with CoSec's own verifier`() {
        runner.withBean(JwtSigning::class.java, { signing }).run { context ->
            context.startupFailure.assert().isNull()
            (authenticate(context, signed()) as AuthnResult.Authenticated).principal.id.assert()
                .isEqualTo(PrincipalId("alice"))
            authenticate(context, "forged").assert().isInstanceOf(AuthnResult.Rejected::class.java)
            StartupSummary.report(context, context.environment).line.assert().contains("jwt=on (JwtTokenVerifier)")
        }
    }

    @Test
    fun `with neither a request with a token is refused`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            // A token CoSec could verify, were there a key: it is refused for there is none.
            val refused = authenticate(context, signed()) as AuthnResult.Rejected
            refused.reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
            authenticate(context, null).assert().isEqualTo(AuthnResult.Anonymous)
            StartupSummary.report(context, context.environment).line.assert().contains("jwt=off")
        }
    }

    @Test
    fun `a verifier without a reader of revocations fails startup naming it`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecPropertyCheckAutoConfiguration::class.java,
                    CoSecAutoConfiguration::class.java,
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("cosec.jwt.enabled=false", "cosec.authorization.routes.location=")
            .withBean(SnapshotDocumentStore::class.java, { InMemorySnapshotDocumentStore() })
            .withBean(TokenVerifier::class.java, { OwnVerifier() })
            .run { context ->
                val messages = generateSequence(
                    context.startupFailure
                ) { it.cause }.map { it.message.orEmpty() }.toList()
                messages.any { "A TokenVerifier bean turns token authentication on" in it && "RevocationReader" in it }
                    .assert().isTrue()
            }
    }

    private companion object {
        const val VALID = "valid-token-of-another-issuer"
    }
}
