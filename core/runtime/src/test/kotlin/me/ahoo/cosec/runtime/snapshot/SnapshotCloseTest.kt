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

package me.ahoo.cosec.runtime.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What [SnapshotSource.close] owes whatever the store does: nothing left running, nobody left waiting. */
class SnapshotCloseTest {
    private val store = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }

    /** #1251: the failure of one step is no reason to skip the others, and is reported once they ran. */
    @Test
    fun `a subscription that cannot close leaves nothing of the source behind`() {
        store.onUnsubscribe = { error("unsubscribe failed") }
        val source = testSource(store, compileExecutor = null)
        source.load().get(WAIT_SECONDS, TimeUnit.SECONDS)
        store.hold = true
        val waiting = source.load("app-a")

        assertThrows<IllegalStateException> { source.close() }.message.assert().isEqualTo("unsubscribe failed")

        source.threadsTerminated.assert().isTrue()
        assertThrows<ExecutionException> { waiting.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        store.onUnsubscribe = { error("closed twice") }
        source.close()
    }

    /** #1251: every failure is reported, and what the caller gave the source is left to the caller. */
    @Test
    fun `later failures are kept with the first and the caller's scheduler stays up`() {
        val periodic = mockk<ScheduledFuture<Any>>()
        every { periodic.cancel(false) } throws IllegalArgumentException("cancel failed")
        val scheduler = mockk<ScheduledExecutorService>()
        every { scheduler.scheduleWithFixedDelay(any(), any(), any(), any()) } returns periodic
        store.onUnsubscribe = { error("unsubscribe failed") }
        val source = testSource(store, scheduler = scheduler)

        val failure = assertThrows<IllegalArgumentException> { source.close() }

        failure.suppressed.map { it.message }.assert().containsExactly("unsubscribe failed")
        verify(exactly = 0) { scheduler.shutdownNow() }
        verify(exactly = 0) { scheduler.shutdown() }
    }

    /** #1266: the read's timeout went with the scheduler, so nothing else would ever end these. */
    @Test
    fun `closing answers everything waiting on a read the store never answers`() {
        store.put(APP_A, document(1))
        val source = testSource(store, compileExecutor = null)
        source.load("app-a").get(WAIT_SECONDS, TimeUnit.SECONDS)
        store.hold = true
        val recompile = source.recompile()
        val recompiling = source.reload(APP_A)
        val reload = source.reload(SnapshotKey.Platform)
        val queued = source.reload(SnapshotKey.Platform)
        val neverInstalled = source.reload(APP_B)
        val revalidation = source.revalidate()
        val tick = source.tick()
        val request = source.load("app-c")

        source.close()

        source.threadsTerminated.assert().isTrue()
        assertThrows<ExecutionException> { request.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        CompletableFuture.allOf(recompile, revalidation, tick).get(WAIT_SECONDS, TimeUnit.SECONDS)
        // A failed read keeps what is installed, and so does one that closing ended.
        reload.get(WAIT_SECONDS, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
        queued.get(WAIT_SECONDS, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
        neverInstalled.get(WAIT_SECONDS, TimeUnit.SECONDS).state.assert().isInstanceOf(DocumentState.Failed::class.java)
        // Compiled with inputs that have changed: as ever, never handed out once a recompile has started.
        recompiling.get(WAIT_SECONDS, TimeUnit.SECONDS).state.assert().isInstanceOf(DocumentState.Failed::class.java)
    }

    /** #1266: closing already ended this load, so what the read finds has nowhere to go; that is no error either. */
    @Test
    fun `a read that answers after close installs nothing and logs no error`() {
        val compiler = FakeCompiler()
        val source = testSource(store, compiler)
        source.load().get(WAIT_SECONDS, TimeUnit.SECONDS)
        store.dropNotifications = true
        store.put(SnapshotKey.Platform, document(2))
        store.hold = true
        val reload = source.reload(SnapshotKey.Platform)
        val errors = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(LOGGER) as Logger
        logger.addAppender(errors)
        try {
            source.close()
            store.releaseAll()
        } finally {
            logger.detachAppender(errors)
        }

        reload.get(WAIT_SECONDS, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
        compiler.compiled.map { it.second }.assert().containsExactly(1L)
        source.entries().single().revision.assert().isEqualTo(1)
        errors.list.filter { it.level == Level.ERROR }.assert().isEmpty()
    }

    /** #1266: whichever of the two ends the load, the other finds nothing left to do and nobody is left waiting. */
    @Test
    fun `closing races a late answer without leaving anyone waiting`() {
        repeat(RACES) {
            val racing = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }
            val source = testSource(racing, compileExecutor = null)
            source.load().get(WAIT_SECONDS, TimeUnit.SECONDS)
            racing.hold = true
            val reload = source.reload(SnapshotKey.Platform)
            val queued = source.reload(SnapshotKey.Platform)
            val revalidation = source.revalidate()
            val answering = CompletableFuture.runAsync { racing.releaseAll() }

            source.close()

            answering.get(WAIT_SECONDS, TimeUnit.SECONDS)
            CompletableFuture.allOf(reload, queued, revalidation).get(WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a tick after close reports nothing and ends`() {
        val metrics = RecordingMetrics()
        val clock = ManualClock()
        val source = testSource(store, metrics = metrics, clock = clock)
        source.load().get(WAIT_SECONDS, TimeUnit.SECONDS)
        source.close()
        clock.advance(Duration.ofHours(1))

        source.tick().get(WAIT_SECONDS, TimeUnit.SECONDS)

        metrics.lagExceeded.assert().isEmpty()
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val RACES = 50
        const val LOGGER = "me.ahoo.cosec.runtime.snapshot"
    }
}
