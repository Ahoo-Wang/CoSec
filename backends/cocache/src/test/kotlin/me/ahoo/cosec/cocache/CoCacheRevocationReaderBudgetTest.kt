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

package me.ahoo.cosec.cocache

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Lookup
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * R2D-8 (`PIP-7`): each read the reader sends to Redis has its own budget. A Redis that hangs is unavailable once it is
 * spent, which the pipeline's verification may accept and a refresh never issues from (`IDN-14`); a lookup CoCache
 * shares with other readers of the key is not failed by one reader's timeout; a local hit is answered as it is.
 */
class CoCacheRevocationReaderBudgetTest {
    private val budget = Duration.ofMillis(BUDGET_MILLIS)

    private fun tokens(answer: CompletableFuture<Lookup<Boolean>>) =
        mockk<RevokedTokenCache> { every { async().lookup(any()) } returns answer }

    private fun subjects(answer: CompletableFuture<Lookup<Long>>) =
        mockk<RevokedSubjectCache> { every { async().lookup(any()) } returns answer }

    @Test
    fun `a token read Redis never answers is unavailable once the budget is spent, and the shared lookup stays open`() {
        val hanging = CompletableFuture<Lookup<Boolean>>()
        val reader = CoCacheRevocationReader(tokens(hanging), readTimeout = budget)
        val started = System.nanoTime()
        val failure = runCatching { runBlocking { reader.isRevoked("jti-1") } }.exceptionOrNull()
        val took = Duration.ofNanos(System.nanoTime() - started)
        failure.assert().isInstanceOf(RevocationUnavailableException::class.java)
        took.assert().isGreaterThanOrEqualTo(budget).isLessThan(budget.multipliedBy(LATE))
        hanging.isDone.assert().isFalse()
    }

    @Test
    fun `a subject read Redis never answers is unavailable once the budget is spent`() {
        val reader = CoCacheRevocationReader(
            tokens(CompletableFuture.completedFuture<Lookup<Boolean>>(Lookup.Absent(null))),
            subjects(CompletableFuture()),
            budget,
        )
        val failure = runCatching {
            runBlocking { reader.subjectRevokedBefore(TenantId("acme"), PrincipalId("alice")) }
        }.exceptionOrNull()
        failure.assert().isInstanceOf(RevocationUnavailableException::class.java)
    }

    @Test
    fun `an answer in time decides as before`() = runBlocking<Unit> {
        val later = CompletableFuture<Lookup<Boolean>>()
        val reader = CoCacheRevocationReader(tokens(later), readTimeout = budget)
        Thread { later.complete(Lookup.Present(true, null)) }.start()
        reader.isRevoked("jti-1").assert().isTrue()
        CoCacheRevocationReader(
            tokens(CompletableFuture.completedFuture<Lookup<Boolean>>(Lookup.Absent(null)))
        ).isRevoked("jti-2")
            .assert().isFalse()
    }

    @Test
    fun `the budget is positive`() {
        for (spent in listOf(Duration.ZERO, Duration.ofMillis(-1))) {
            runCatching { CoCacheRevocationReader(tokens(CompletableFuture()), readTimeout = spent) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        CoCacheRevocationReader.DEFAULT_READ_TIMEOUT.assert().isEqualTo(Duration.ofMillis(300))
    }

    private companion object {
        const val BUDGET_MILLIS = 100L

        /** What a loaded runner may add past the budget: never several times it. */
        const val LATE = 10L
    }
}
