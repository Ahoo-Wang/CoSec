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

package me.ahoo.cosec.runtime.authn

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.runtime.token.CoSecTokenVerifier
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.PERSON
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.WORKLOAD
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `IDN-46`: the credential a token authenticates is of the kind its verifier read, and only a person's uses break-glass. */
class TokenKindTest {
    private fun token(kind: Identity.Credential.Kind) = VerifiedToken("jti-1", "cosec", "(platform)", null, kind)

    private class Own(private val token: VerifiedToken) : CoSecTokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = token

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private class Other(private val token: VerifiedToken) : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = token

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private suspend fun authenticate(verifier: TokenVerifier): AuthenticatedPrincipal =
        (
            TokenAuthenticator(
                verifier,
                RevocationReader.NONE,
                acceptUnavailableRevocations = false,
            ).authenticate(Credentials("t", null)) as AuthnResult.Authenticated
            )
            .principal

    @Test
    fun `a token's credential is of the kind its verifier read, and only CoSec's own person's token uses break-glass - IDN-46`() =
        runTest {
            val person = authenticate(Own(token(PERSON)))
            person.credential!!.kind.assert().isEqualTo(PERSON)
            person.breakGlass.assert().isTrue()
            person.workload.assert().isFalse()

            val workload = authenticate(Own(token(WORKLOAD)))
            workload.credential!!.kind.assert().isEqualTo(WORKLOAD)
            workload.credential!!.method.assert().isEqualTo(Identity.Method.JWT)
            workload.breakGlass.assert().isFalse()
            workload.workload.assert().isTrue()

            listOf(PERSON, WORKLOAD).forEach { kind ->
                val other = authenticate(Other(token(kind)))
                other.credential!!.kind.assert().isEqualTo(kind)
                other.breakGlass.assert().isFalse()
            }
        }
}
