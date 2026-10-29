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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.claims
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.ThreadingFixtures.CLIENT_IO
import me.ahoo.cosec.runtime.ThreadingFixtures.CountingDispatcher
import me.ahoo.cosec.runtime.ThreadingFixtures.EVENT_LOOP
import me.ahoo.cosec.runtime.ThreadingFixtures.completedOn
import me.ahoo.cosec.runtime.ThreadingFixtures.singleThread
import me.ahoo.cosec.runtime.ThreadingFixtures.startOn
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * T1 (RFC 0006 §7.2): when nothing really suspends (snapshot installed, memberships from claims, no external I/O), the
 * whole evaluation finishes on the thread that called it, and the resume dispatcher is never asked for a dispatch.
 */
class PipelineHotPathTest {
    private val eventLoop = singleThread(EVENT_LOOP)
    private val clientIo = singleThread(CLIENT_IO)
    private val resume = CountingDispatcher()

    @AfterEach
    fun close() {
        eventLoop.shutdownNow()
        clientIo.shutdownNow()
    }

    private fun assertHotPath(pipeline: Pipeline, engine: StubEngine) {
        val (outcome, finishedOn) = startOn(eventLoop, ResumeDispatcher(resume)) { pipeline.evaluate(facts()) }
        outcome.proceed.assert().isTrue()
        finishedOn.name.assert().isEqualTo(EVENT_LOOP)
        engine.threads.single().name.assert().isEqualTo(EVENT_LOOP)
        resume.dispatches.get().assert().isZero()
    }

    @Test
    fun anonymousRequestStaysOnTheCallingThread() {
        val engine = StubEngine(PERMIT)
        assertHotPath(pipeline(engine = engine), engine)
    }

    @Test
    fun authenticatedRequestWithClaimsStaysOnTheCallingThread() {
        val engine = StubEngine(PERMIT)
        assertHotPath(pipeline(authenticator(authenticated(claims("" to "admin"))), engine), engine)
    }

    /** The control: the same harness does see a dispatch when an SPI really suspends. */
    @Test
    fun realSuspensionIsDispatched() {
        val engine = StubEngine(PERMIT)
        val suspending = Authenticator { completedOn(clientIo, AuthnResult.Anonymous).await() }
        val (_, finishedOn) = startOn(eventLoop, ResumeDispatcher(resume)) {
            pipeline(suspending, engine).evaluate(facts())
        }
        resume.dispatches.get().assert().isEqualTo(1)
        finishedOn.name.assert().isNotEqualTo(EVENT_LOOP)
    }
}
