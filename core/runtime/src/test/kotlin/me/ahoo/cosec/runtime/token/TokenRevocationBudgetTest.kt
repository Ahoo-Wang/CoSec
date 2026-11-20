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

package me.ahoo.cosec.runtime.token

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * R2D-8: the revocation check's reads have a budget together. A store that hangs is unavailable once it is spent, which
 * every caller judges as it judges a refused read: the pipeline's verification may accept the token (`PIP-7`), a refresh
 * or a tenant switch never issues from it (`IDN-14`). A store that answers at once is not timed.
 */
class TokenRevocationBudgetTest {
    private val token = VerifiedToken("jti-1", "alice", "tenant-a", null)

    private object Hanging : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = awaitCancellation()

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = awaitCancellation()
    }

    @Test
    fun `a hanging store is unavailable once the budget is spent`() = runTest {
        val revocation = TokenRevocation(Hanging, readTimeout = Duration.ofMillis(300))
        val failure = runCatching { revocation.check(token) }.exceptionOrNull()
        failure.assert().isInstanceOf(RevocationUnavailableException::class.java)
        // The budget runs from the call in real time, so the virtual clock stops within a millisecond short of it.
        currentTime.assert().isBetween(299L, 300L)
    }

    @Test
    fun `a store that answers at once decides as before`() = runTest {
        val revocation = TokenRevocation(RevocationReader.NONE)
        revocation.check(token)
        currentTime.assert().isZero()
    }

    @Test
    fun `the budget is positive`() {
        for (spent in listOf(Duration.ZERO, Duration.ofMillis(-1))) {
            runCatching { TokenRevocation(RevocationReader.NONE, readTimeout = spent) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        TokenRevocation.DEFAULT_READ_TIMEOUT.assert().isEqualTo(Duration.ofMillis(300))
    }
}
