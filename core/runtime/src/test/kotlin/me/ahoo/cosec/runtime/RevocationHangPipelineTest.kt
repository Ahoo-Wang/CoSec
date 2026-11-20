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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

/**
 * R2D-8 (`PIP-7`): a revocation store that hangs is answered as one that refuses, within the revocation check's own
 * budget, before the authenticator's runs out: the token is accepted by default and the request decided, or, with
 * `fail-closed`, the request is 503. Whichever of the two reads hangs, the token's or its principal's.
 */
class RevocationHangPipelineTest {
    private object Verifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = VerifiedToken("jti-1", "alice", "tenant-a", null)

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    /** A store that never answers the read named [hung]; the other answers "not revoked". */
    private class Hanging(private val hung: String) : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = if (hung == TOKEN) awaitCancellation() else false

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            if (hung == SUBJECT) awaitCancellation() else null
    }

    private fun authenticator(hung: String, failClosed: Boolean, unchecked: UncheckedRevocations = UncheckedRevocations()) =
        TokenAuthenticator(
            Verifier,
            Hanging(hung),
            acceptUnavailableRevocations = !failClosed,
            unchecked = unchecked,
            revocationReadTimeout = BUDGET,
        )

    private val request = facts(headers = mapOf("Authorization" to listOf("Bearer t")))

    @ParameterizedTest
    @ValueSource(strings = [TOKEN, SUBJECT])
    fun `a hanging revocation read accepts the token within its budget`(hung: String) {
        val unchecked = UncheckedRevocations()
        val started = System.nanoTime()
        val outcome = runBlocking { pipeline(authenticator(hung, failClosed = false, unchecked)).evaluate(request) }
        val took = Duration.ofNanos(System.nanoTime() - started)
        outcome.proceed.assert().describedAs("$outcome").isTrue()
        outcome.security!!.principal!!.id.assert().isEqualTo(PrincipalId("alice"))
        unchecked.count.assert().isEqualTo(1)
        took.assert().isGreaterThanOrEqualTo(BUDGET).isLessThan(AUTHENTICATION_TIMEOUT)
    }

    @ParameterizedTest
    @ValueSource(strings = [TOKEN, SUBJECT])
    fun `with fail-closed a hanging revocation read is a 503 within its budget`(hung: String) {
        val started = System.nanoTime()
        val outcome = runBlocking { pipeline(authenticator(hung, failClosed = true)).evaluate(request) }
        val took = Duration.ofNanos(System.nanoTime() - started)
        outcome.status.assert().isEqualTo(ProblemType.UNAVAILABLE.status)
        outcome.problem!!.type.assert().isEqualTo(ProblemType.UNAVAILABLE)
        took.assert().isLessThan(AUTHENTICATION_TIMEOUT)
    }

    private companion object {
        const val TOKEN = "token"
        const val SUBJECT = "subject"
        val BUDGET: Duration = Duration.ofMillis(100)

        /** The pipeline's default authentication timeout: what a hang ran into before. */
        val AUTHENTICATION_TIMEOUT: Duration = Duration.ofSeconds(1)
    }
}
