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

package me.ahoo.cosec.runtime.http

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** UA-3, `PIP-26`: a transport of the application's own ends its request by closing the [Outcome]. */
class OutcomeCloseTest {
    private val pipeline = PipelineFixtures.pipeline(authenticator = authenticator(authenticated()))
    private val checker = AuthorizationChecker(pipeline)

    private fun evaluate(): Outcome = runBlocking { pipeline.evaluate(facts("/orders/1")) }

    private fun permits(outcome: Outcome): Boolean =
        runBlocking { checker.check(outcome.security!!.securityContext, "order:read", "order/42") }

    @Test
    fun `closing the outcome ends the request it permitted`() {
        val kept = evaluate().use { outcome ->
            outcome.proceed.assert().isTrue()
            permits(outcome).assert().isTrue()
            outcome
        }
        permits(kept).assert().isFalse()
        kept.security!!.inProgress.assert().isFalse()
        kept.close()
        permits(kept).assert().isFalse()
        // Ending one request ends no other.
        permits(evaluate()).assert().isTrue()
    }

    @Test
    fun `an adapter's end and a close are one end, in either order`() {
        val endedFirst = evaluate()
        endedFirst.security!!.end()
        endedFirst.close()
        val closedFirst = evaluate()
        closedFirst.close()
        closedFirst.security!!.end()
        listOf(endedFirst, closedFirst).forEach { outcome ->
            outcome.security!!.inProgress.assert().isFalse()
            permits(outcome).assert().isFalse()
        }
    }

    @Test
    fun `closing a refused outcome does nothing`() {
        val refusing = PipelineFixtures.pipeline(engine = PipelineFixtures.StubEngine(Decision.Deny(null)))
        val outcome = runBlocking { refusing.evaluate(facts("/orders/1")) }
        outcome.proceed.assert().isFalse()
        outcome.close()
        outcome.close()
        outcome.security.assert().isNull()
    }
}
