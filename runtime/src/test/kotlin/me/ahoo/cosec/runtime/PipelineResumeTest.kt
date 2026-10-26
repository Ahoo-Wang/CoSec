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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.runtime.PipelineFixtures.InstalledViews
import me.ahoo.cosec.runtime.PipelineFixtures.LoadingViews
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.ThreadingFixtures.CLIENT_IO
import me.ahoo.cosec.runtime.ThreadingFixtures.CountingDispatcher
import me.ahoo.cosec.runtime.ThreadingFixtures.EVENT_LOOP
import me.ahoo.cosec.runtime.ThreadingFixtures.completedOn
import me.ahoo.cosec.runtime.ThreadingFixtures.singleThread
import me.ahoo.cosec.runtime.ThreadingFixtures.startOn
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * T2 and T3 (RFC 0006 §7.2): after an SPI call really suspends, the pipeline goes on on the adapter's resume dispatcher
 * (WebFlux, Gateway) or the request thread (WebMvc), never on the client library's thread that completed the call; and
 * only the context the adapter built decides which.
 */
class PipelineResumeTest {
    private val eventLoop = singleThread(EVENT_LOOP)
    private val clientIo = singleThread(CLIENT_IO)
    private val resumeThread = singleThread(RESUME)
    private val resume = CountingDispatcher(resumeThread.asCoroutineDispatcher())

    @AfterEach
    fun close() {
        listOf(eventLoop, clientIo, resumeThread).forEach { it.shutdownNow() }
    }

    private fun suspendingAuthenticator() = Authenticator { completedOn(clientIo, AuthnResult.Anonymous).await() }

    private fun assertResumedOn(expected: String, pipeline: Pipeline, engine: StubEngine) {
        val (outcome, finishedOn) = startOn(eventLoop, ResumeDispatcher(resume)) { pipeline.evaluate(facts()) }
        outcome.proceed.assert().isTrue()
        engine.threads.single().name.assert().isEqualTo(expected)
        finishedOn.name.assert().isEqualTo(expected)
    }

    @Test
    fun authenticatorCompletingOnAClientThreadResumesOnTheResumeDispatcher() {
        val engine = StubEngine(PERMIT)
        assertResumedOn(RESUME, pipeline(suspendingAuthenticator(), engine), engine)
        resume.dispatches.get().assert().isEqualTo(1)
    }

    @Test
    fun membershipProviderCompletingOnAClientThreadResumesOnTheResumeDispatcher() {
        val engine = StubEngine(PERMIT)
        val provider = MembershipProvider { _, _ ->
            completedOn(clientIo, Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
        }
        assertResumedOn(
            RESUME,
            pipeline(authenticator(authenticated(null)), engine, membershipProvider = provider),
            engine
        )
    }

    @Test
    fun snapshotLoadCompletingOnAClientThreadResumesOnTheResumeDispatcher() {
        val engine = StubEngine(PERMIT)
        val installed = InstalledViews()
        val views = LoadingViews { key -> completedOn(clientIo, installed.peek(key)) }
        assertResumedOn(RESUME, pipeline(engine = engine, views = views), engine)
    }

    /** The WebFlux bridge resumes on `Dispatchers.Default` (RFC 0006 §7.3). */
    @Test
    fun defaultDispatcherAsTheResumeDispatcher() {
        val engine = StubEngine(PERMIT)
        val pipeline = pipeline(suspendingAuthenticator(), engine)
        val (_, finishedOn) = startOn(eventLoop, ResumeDispatcher(Dispatchers.Default)) { pipeline.evaluate(facts()) }
        engine.threads.single().name.assert().startsWith("DefaultDispatcher-worker")
        finishedOn.name.assert().startsWith("DefaultDispatcher-worker")
    }

    /** The WebMvc bridge puts no resume dispatcher: `runBlocking`'s interceptor brings the pipeline back to its thread. */
    @Test
    fun withoutAResumeDispatcherTheInterceptorDecides() {
        val engine = StubEngine(PERMIT)
        val pipeline = pipeline(suspendingAuthenticator(), engine)
        val requestThread = Thread.currentThread()
        val outcome = runBlocking { pipeline.evaluate(facts()) }
        outcome.proceed.assert().isTrue()
        engine.threads.single().assert().isSameAs(requestThread)
    }

    private companion object {
        const val RESUME = "test-resume"
    }
}
