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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R2D-11: the compile phase of a load has its load's deadline. A compilation that has not answered when the load's
 * budget is spent (`coldLoadTimeout` for a cold load, `storeReadTimeout` for a reload, since it began) ends the load as
 * a failure at the next periodic tick: a cold load answers failed, a reload keeps what is installed, a recompile fails
 * closed. A compilation that never returns holds one thread, never more: its revision is not compiled again while it
 * runs, and a queued compilation whose load timed out never starts.
 */
class SnapshotCompileDeadlineTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
    }
    private val pool = Executors.newSingleThreadExecutor()
    private val compiler = FakeCompiler()
    private val elapsed = ManualNanoTime()

    @AfterEach
    fun close() {
        compiler.gate?.countDown()
        pool.shutdownNow()
    }

    /** The platform document compiled before the gate closes, so only app documents meet it. */
    private fun source(executor: Executor = pool) = testSource(store, compiler, compileExecutor = executor).also {
        it.nanoTime = elapsed
        it.load().get(BOUND_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time." }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `a cold load whose compilation never returns fails at the tick after its deadline, and holds one thread`() {
        source().use { source ->
            val gate = CountDownLatch(1).also { compiler.gate = it }
            val cold = source.load("app-a")
            awaitCondition { compiler.threads.size == 2 }
            // Before its deadline the tick leaves it alone.
            elapsed.advance(SnapshotOptions().coldLoadTimeout)
            source.tick()
            cold.isDone.assert().isFalse()
            elapsed.advance(Duration.ofSeconds(1))
            source.tick()
            awaitCondition { cold.isDone }
            cold.join().app.assert().isInstanceOf(DocumentState.Failed::class.java)

            // While that compilation runs, its revision fails at once and takes no thread.
            source.load("app-a").get(BOUND_SECONDS, TimeUnit.SECONDS).app
                .assert().isInstanceOf(DocumentState.Failed::class.java)
            compiler.threads.size.assert().isEqualTo(2)

            // Once it returns, the revision compiles again.
            gate.countDown()
            compiler.gate = null
            awaitCondition {
                source.load("app-a").get(BOUND_SECONDS, TimeUnit.SECONDS).app is DocumentState.Present
            }
        }
    }

    @Test
    fun `a reload whose compilation never returns keeps what is installed once its budget is spent`() {
        source().use { source ->
            source.load("app-a").get(BOUND_SECONDS, TimeUnit.SECONDS)
            val gate = CountDownLatch(1).also { compiler.gate = it }
            store.put(APP_A, document(2, "a2"))
            val reload = source.reload(APP_A)
            awaitCondition { compiler.threads.size == 3 }
            elapsed.advance(SnapshotOptions().storeReadTimeout.plusSeconds(1))
            source.tick()
            awaitCondition { reload.isDone }
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(1)
            gate.countDown()
        }
    }

    @Test
    fun `a compilation still queued when its load times out never starts`() {
        val queued = CopyOnWriteArrayList<Runnable>()
        val parking = Executor { queued += it }
        source(directExecutor).close()
        testSource(store, compiler, compileExecutor = { task -> parking.execute(task) }).use { source ->
            source.nanoTime = elapsed
            val compiledBefore = compiler.compiled.size
            val cold = source.load("app-a")
            // The platform's and the app's compilations wait in the queue.
            awaitCondition { queued.size == 2 }
            queued.removeAt(0).run()
            elapsed.advance(SnapshotOptions().coldLoadTimeout.plusSeconds(1))
            source.tick()
            awaitCondition { cold.isDone }
            queued.removeAt(0).run()
            compiler.compiled.size.assert().isEqualTo(compiledBefore + 1)
        }
    }

    private companion object {
        /** How long a test waits for what it expects; it fails past it. */
        const val BOUND_SECONDS = 5L
    }
}
