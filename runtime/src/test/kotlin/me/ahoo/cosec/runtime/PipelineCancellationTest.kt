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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.runtime.PipelineFixtures.LoadingViews
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

/**
 * `PIP-3` (RFC 0006 §7.4, §10.2): an external cancellation at any suspension point propagates out of
 * [Pipeline.evaluate] and makes no [Outcome], so an adapter has nothing that could proceed. The pipeline's own deadline
 * is not a cancellation: it answers 503.
 */
class PipelineCancellationTest {
    private class Cancelled(val outcome: Outcome?, val thrown: Throwable?)

    /** Evaluates with [pipeline], cancels once [suspended] completes, and reports what evaluate did. */
    private fun cancelWhenSuspended(pipeline: Pipeline, suspended: CompletableDeferred<Unit>): Cancelled = runBlocking {
        var outcome: Outcome? = null
        var thrown: Throwable? = null
        val job = launch {
            try {
                outcome = pipeline.evaluate(facts())
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        suspended.await()
        job.cancelAndJoin()
        job.isCancelled.assert().isTrue()
        Cancelled(outcome, thrown)
    }

    private fun Cancelled.assertNoOutcome(engine: StubEngine) {
        outcome.assert().isNull()
        thrown.assert().isInstanceOf(CancellationException::class.java)
        engine.requests.assert().isEmpty()
    }

    @Test
    fun cancelledWhileAuthenticating() {
        val suspended = CompletableDeferred<Unit>()
        val engine = StubEngine(PERMIT)
        val authenticator = Authenticator {
            suspended.complete(Unit)
            awaitCancellation()
        }
        cancelWhenSuspended(pipeline(authenticator, engine), suspended).assertNoOutcome(engine)
    }

    @Test
    fun cancelledWhileResolvingMemberships() {
        val suspended = CompletableDeferred<Unit>()
        val engine = StubEngine(PERMIT)
        val shared = CompletableFuture<Memberships>()
        val provider = MembershipProvider { _, _ ->
            suspended.complete(Unit)
            shared
        }
        cancelWhenSuspended(
            pipeline(authenticator(authenticated(null)), engine, membershipProvider = provider),
            suspended
        )
            .assertNoOutcome(engine)
        // The provider may share its future with other requests: only the pipeline's copy is cancelled.
        shared.isCancelled.assert().isFalse()
    }

    @Test
    fun cancelledWhileLoadingTheSnapshot() {
        val suspended = CompletableDeferred<Unit>()
        val engine = StubEngine(PERMIT)
        val own = CompletableFuture<SnapshotView>()
        val views = LoadingViews {
            suspended.complete(Unit)
            own
        }
        cancelWhenSuspended(pipeline(engine = engine, views = views), suspended).assertNoOutcome(engine)
        // Each caller gets its own future from the source, so cancelling it leaves a shared load running.
        own.isCancelled.assert().isTrue()
    }

    @Test
    fun ownDeadlineIsAnOutcomeNotACancellation() = runTest {
        val outcome = pipeline(Authenticator { awaitCancellation() }, options = PipelineOptions(timeout = DEADLINE))
            .evaluate(facts())
        outcome.status.assert().isEqualTo(503)
        outcome.proceed.assert().isFalse()
    }

    /** RFC 0006 §7.4 rule 3: an Error is not caught; the host answers it. */
    @Test
    fun errorsPropagate() {
        val pipeline = pipeline(engine = StubEngine { throw StackOverflowError() })
        assertThrows<StackOverflowError> { runBlocking { pipeline.evaluate(facts()) } }
    }

    private companion object {
        val DEADLINE = kotlin.time.Duration.parse("200ms")
    }
}
