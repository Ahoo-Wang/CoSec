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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.PipelineFixtures.IMPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.PERSON
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `IDN-38` through the pipeline: a blank principal id is no one, refused where it enters. A token naming one is an
 * invalid credential (401 `token-invalid`); an authenticator that makes one has failed (503), as for `IDN-10`. White
 * space around an id is part of it.
 */
class BlankPrincipalIdPipelineTest {
    private class Verifier(private val subject: String) : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = VerifiedToken("jti", subject, "acme", null, PERSON)

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private val withToken = facts(headers = mapOf("Authorization" to listOf("Bearer token")))

    @Test
    fun `a token of a blank subject is token-invalid and decided as anonymous`() = runTest {
        for (subject in listOf("", " ", "\t\n", " ")) {
            val engine = StubEngine(IMPLICIT_DENY)
            val authenticator = TokenAuthenticator(Verifier(subject), RevocationReader.NONE)
            val outcome = pipeline(authenticator, engine).evaluate(withToken)

            outcome.status.assert().describedAs(subject).isEqualTo(ProblemType.TOKEN_INVALID.status)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.TOKEN_INVALID)
            engine.requests.single().request.subject.authenticated.assert().isFalse()
        }
    }

    @Test
    fun `white space around a subject is part of its id`() = runTest {
        val engine = StubEngine(IMPLICIT_DENY)
        pipeline(TokenAuthenticator(Verifier(" alice "), RevocationReader.NONE), engine).evaluate(withToken)
        engine.requests.single().request.subject.id.assert().isEqualTo(" alice ")
    }

    /** The backstop: the principal cannot be made, so the authenticator throws, and the request is 503, never decided. */
    @Test
    fun `an authenticator whose principal is blank is unavailable`() = runTest {
        for (id in listOf("", " ", "\t")) {
            val engine = StubEngine(IMPLICIT_DENY)
            val outcome = pipeline(
                Authenticator {
                    AuthnResult.Authenticated(
                        AuthenticatedPrincipal(PrincipalId(id), TenantId("acme"), null),
                        Identity.Method("custom"),
                    )
                },
                engine,
            ).evaluate(facts())

            outcome.status.assert().describedAs(id).isEqualTo(ProblemType.UNAVAILABLE.status)
            outcome.proceed.assert().isFalse()
            engine.requests.assert().isEmpty()
        }
    }
}
