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
 * R2D-8: CoSec bounds the reads of its own revocation reader (`CoCacheRevocationReader`'s read timeout); an
 * application's own [RevocationReader] bounds its own, or the authenticator's timeout does it: a reader that never
 * answers makes the request 503 at `cosec.pipeline.authentication-timeout`, accepted or not by `fail-closed`, since the
 * check never reached an answer. What `extending.md` tells a reader's author, held here.
 */
class RevocationHangPipelineTest {
    private object Verifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = VerifiedToken("jti-1", "alice", "tenant-a", null)

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    /** An application's reader that never answers the read named [hung]; the other answers "not revoked". */
    private class Hanging(private val hung: String) : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = if (hung == TOKEN) awaitCancellation() else false

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            if (hung == SUBJECT) awaitCancellation() else null
    }

    private val request = facts(headers = mapOf("Authorization" to listOf("Bearer t")))

    @ParameterizedTest
    @ValueSource(strings = [TOKEN, SUBJECT])
    fun `a custom reader that never answers is a 503 at the authentication timeout, whatever fail-closed says`(
        hung: String
    ) {
        for (failClosed in listOf(false, true)) {
            val authenticator = TokenAuthenticator(Verifier, Hanging(hung), acceptUnavailableRevocations = !failClosed)
            val pipeline = pipeline(authenticator) { options { authenticationTimeout = AUTHENTICATION_TIMEOUT } }
            val started = System.nanoTime()
            val outcome = runBlocking { pipeline.evaluate(request) }
            val took = Duration.ofNanos(System.nanoTime() - started)
            outcome.status.assert().describedAs("fail-closed=$failClosed").isEqualTo(ProblemType.UNAVAILABLE.status)
            took.assert().isGreaterThanOrEqualTo(
                AUTHENTICATION_TIMEOUT
            ).isLessThan(AUTHENTICATION_TIMEOUT.multipliedBy(LATE))
        }
    }

    private companion object {
        const val TOKEN = "token"
        const val SUBJECT = "subject"
        val AUTHENTICATION_TIMEOUT: Duration = Duration.ofMillis(200)

        /** What a loaded CI runner may add past the timeout: never as much again several times over. */
        const val LATE = 10L
    }
}
