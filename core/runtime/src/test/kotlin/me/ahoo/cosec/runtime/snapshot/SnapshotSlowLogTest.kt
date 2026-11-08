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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #1252: the scheduler thread keeps time and nothing else, so a log appender that stalls cannot delay a timeout. */
class SnapshotSlowLogTest {
    private val store = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }

    /** Holds the first log line that contains [text], on whichever thread writes it, until [release]. */
    private class BlockingAppender(private val text: String) : UnsynchronizedAppenderBase<ILoggingEvent>() {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        @Volatile
        var thread: Thread? = null

        override fun append(event: ILoggingEvent) {
            if (text in event.formattedMessage && entered.count > 0) {
                thread = Thread.currentThread()
                entered.countDown()
                released.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun release() = released.countDown()
    }

    private fun holdingLogLines(text: String, block: (BlockingAppender) -> Unit) {
        val appender = BlockingAppender(text).apply { start() }
        val logger = LoggerFactory.getLogger(LOGGER) as Logger
        logger.addAppender(appender)
        try {
            block(appender)
        } finally {
            appender.release()
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a stalled lag warning does not delay a cold load timeout`() {
        val clock = ManualClock()
        val options = SnapshotOptions(
            revalidateInterval = SnapshotOptions.MIN_REVALIDATE_INTERVAL,
            lagWarn = Duration.ofSeconds(2),
            coldLoadTimeout = Duration.ofMillis(COLD_LOAD_TIMEOUT_MILLIS),
        )
        holdingLogLines(LAG_LINE) { appender ->
            testSource(store, options = options, clock = clock).use { source ->
                try {
                    source.load().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
                    // Held first: a tick between the two would confirm the document, and it would not lag at all.
                    store.hold = true
                    clock.advance(Duration.ofSeconds(3))
                    appender.entered.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS).assert().isTrue()

                    val answer = source.load("cold-app").get(WITHIN_SECONDS, TimeUnit.SECONDS)

                    answer.app.assert().isInstanceOf(DocumentState.Failed::class.java)
                    appender.thread!!.name.assert().startsWith(HOUSEKEEPING)
                } finally {
                    appender.release()
                }
            }
        }
    }

    @Test
    fun `the warning about a stalled pass is written off the thread that ticks`() {
        val compiler = FakeCompiler()
        val nanos = ManualNanoTime()
        holdingLogLines(STALLED_LINE) { appender ->
            val options = SnapshotOptions(revalidateInterval = QUIET_PASS)
            testSource(store, compiler, options, compileExecutor = null).use { source ->
                source.nanoTime = nanos
                source.load().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
                // No notification: its load would compile past the gate, and leave the pass nothing to stall on.
                store.dropNotifications = true
                store.put(SnapshotKey.Platform, document(2))
                val gate = CountDownLatch(1)
                compiler.gate = gate
                try {
                    source.tick()
                    SnapshotSourceTest.await { compiler.threads.size == 2 }
                    nanos.advance(options.storeReadTimeout.plusSeconds(1))

                    source.tick()

                    appender.entered.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
                    appender.thread!!.name.assert().startsWith(HOUSEKEEPING)
                } finally {
                    appender.release()
                    gate.countDown()
                }
            }
        }
    }

    private companion object {
        const val LOGGER = "me.ahoo.cosec.runtime.snapshot"
        const val HOUSEKEEPING = "cosec-snapshot-housekeeping"
        const val LAG_LINE = "has not been confirmed"
        const val STALLED_LINE = "finished no document"
        const val COLD_LOAD_TIMEOUT_MILLIS = 50L
        const val WITHIN_SECONDS = 3L
        const val TEST_LIMIT_SECONDS = 10L
    }
}
