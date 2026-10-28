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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.claims
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [withLazyTimeoutOrNull] arms a timer only once its block really suspends (the end-to-end slice's measurement, RFC 0008
 * §3.8), and otherwise behaves as `withTimeoutOrNull`.
 */
class LazyTimeoutTest {
    /** Runs everything inline and counts the timers asked of it: the dispatcher is the `Delay` timeouts use. */
    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private class CountingTimers : CoroutineDispatcher(), Delay {
        val timers = AtomicInteger()

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            timers.incrementAndGet()
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            timers.incrementAndGet()
            return DisposableHandle { }
        }
    }

    /** Starts [block] in [context] and returns its result; it must complete before startCoroutine returns. */
    private fun <T> inline(context: CoroutineContext, block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(context) { result = it })
        return checkNotNull(result) { "The block suspended." }.getOrThrow()
    }

    @Test
    fun aRequestThatNeverSuspendsArmsNoTimer() {
        val timers = CountingTimers()
        val engine = StubEngine(PERMIT)
        val pipeline = pipeline(authenticator(authenticated(claims("" to "admin"))), engine)
        inline(timers) { pipeline.evaluate(facts()) }.proceed.assert().isTrue()
        timers.timers.get().assert().isZero()
    }

    @Test
    fun aBlockThatCompletesWithoutSuspendingArmsNoTimer() {
        val timers = CountingTimers()
        inline(timers) { withLazyTimeoutOrNull(1.seconds) { "done" } }.assert().isEqualTo("done")
        timers.timers.get().assert().isZero()
    }

    @Test
    fun aBlockThatSuspendsArmsTheTimer() = runTest {
        withLazyTimeoutOrNull(1.seconds) {
            yield()
            "done"
        }.assert().isEqualTo("done")
        withLazyTimeoutOrNull<String>(100.milliseconds) { awaitCancellation() }.assert().isNull()
    }

    /** The timer counts from the call, not from the first suspension. */
    @Test
    fun theTimeoutCountsFromTheCall() {
        runBlocking {
            val result = withLazyTimeoutOrNull(100.milliseconds) {
                Thread.sleep(150)
                delay(1)
                "late"
            }
            result.assert().isNull()
        }
    }

    @Test
    fun failuresPropagate() = runTest {
        val error = try {
            withLazyTimeoutOrNull<String>(1.seconds) { throw IllegalStateException("boom") }
            null
        } catch (e: IllegalStateException) {
            e
        }
        error!!.message.assert().isEqualTo("boom")
    }

    @Test
    fun inlineNeedsNoContext() {
        inline(EmptyCoroutineContext) { withLazyTimeoutOrNull(1.seconds) { 1 } }.assert().isEqualTo(1)
    }
}
