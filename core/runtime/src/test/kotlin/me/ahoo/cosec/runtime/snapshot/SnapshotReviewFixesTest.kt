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

import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind

import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Regressions for the round-1 review of the snapshot source (#988). */
class SnapshotReviewFixesTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(7, "a7"))
        put(APP_B, document(1, "b1"))
    }

    @Test
    fun coldLoadFromALocalCopyIsConfirmedAtOnce() {
        store.localCopies[APP_A] = document(6, "a6")
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            source.load("app-a").join()

            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(7)
            metrics.regressions.assert().isEmpty()
        }
    }

    @Test
    fun coldLoadOfASeenKeyNeverInstallsAnOlderLocalCopy() {
        val metrics = RecordingMetrics()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 1)
        testSource(store, options = options, metrics = metrics).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            await { !source.holds(APP_A) }
            store.localCopies[APP_A] = document(6, "a6")

            val reloaded = source.load("app-a").join()

            reloaded.appCompiled.revision.assert().isEqualTo(7)
            metrics.installs.filter { it.key == APP_A }.map { it.revision }.assert().containsOnly(7L)
            metrics.regressions.assert().isEmpty()
        }
    }

    @Test
    fun throwingMetricsChangeNothing() {
        testSource(store, metrics = ThrowingMetrics()).use { source ->
            store.hold = true
            val cold = source.load()
            store.put(SnapshotKey.Platform, document(2))
            store.pending.removeAt(0).release()

            cold.join().policiesCompiled.revision.assert().isEqualTo(1)
            store.pending.assert().hasSize(1)
            store.releaseAll()
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)

            store.hold = false
            store.put(SnapshotKey.Platform, document(1, "lost write"))
            source.peek()!!.policiesCompiled.entries.assert().containsExactly("lost write")
            store.remove(SnapshotKey.Platform)
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun throwingLoadMetricStillAnswersTimedOutRequests() {
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, coldLoadTimeout = Duration.ofMillis(20))
        testSource(store, options = options, metrics = ThrowingMetrics()).use { source ->
            store.hold = true
            val answer = source.load().get(10, TimeUnit.SECONDS)
            val failure = (answer.policies as DocumentState.Failed).error
            failure.cause.assert().isInstanceOf(java.util.concurrent.TimeoutException::class.java)
        }
    }

    @Test
    fun loadMetricThrowingAnErrorStillCompletesTheRequest() {
        val metrics = object : SnapshotMetrics {
            override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) = throw AssertionError("meter broken")
        }
        testSource(store, metrics = metrics).use { source ->
            store.failure = IllegalStateException("redis down")
            val answer = source.load()
            answer.isDone.assert().isTrue()
            answer.join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun documentThatFailedToCompileIsCompiledAgainOnRevalidation() {
        val compiler = FakeCompiler()
        compiler.failure = IllegalStateException("matcher not registered yet")
        testSource(store, compiler = compiler).use { source ->
            source.load().join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)

            compiler.failure = null
            source.revalidate().join()

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun reloadReadsAndCompilesInFull() {
        val compiler = FakeCompiler()
        testSource(store, compiler = compiler).use { source ->
            source.load().join()
            val compiles = compiler.compiled.size

            source.reload(SnapshotKey.Platform).join().revision.assert().isEqualTo(1)

            compiler.compiled.size.assert().isEqualTo(compiles + 1)
        }
    }

    @Test
    fun reloadDuringALoadAnswersWithTheLoadAfterIt() {
        testSource(store).use { source ->
            store.hold = true
            val cold = source.load()
            val reloaded = source.reload(SnapshotKey.Platform)
            store.put(SnapshotKey.Platform, document(2))
            store.pending.removeAt(0).release()
            cold.join().policiesCompiled.revision.assert().isEqualTo(1)
            reloaded.isDone.assert().isFalse()

            store.releaseAll()

            reloaded.join().revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun virtualMachineErrorInACompilationFailsTheLoadWithoutWedgingTheKey() {
        val compiler = FakeCompiler()
        compiler.failure = OutOfMemoryError("test")
        testSource(store, compiler = compiler, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS).policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.peek().assert().isNull()

            compiler.failure = null
            source.load().get(10, TimeUnit.SECONDS).policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun keyReadButNeverInstalledStillCountsAsSeen() {
        val rejecting = Executor { throw RejectedExecutionException("full") }
        testSource(store, compileExecutor = rejecting).use { source ->
            source.load().join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.peek().assert().isNull()

            store.remove(SnapshotKey.Platform)

            val failure = (source.load().join().policies as DocumentState.Failed).error
            failure.message.assert().contains("was seen")
        }
    }

    @Test
    fun storeThatNeverAnswersFailsTheLoadAndFreesTheKey() {
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, storeReadTimeout = Duration.ofMillis(50))
        testSource(store, options = options).use { source ->
            store.hold = true

            source.load().get(10, TimeUnit.SECONDS).policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            store.pending.assert().hasSize(1)

            store.hold = false
            source.load().get(10, TimeUnit.SECONDS).policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun undecodableDocumentIsNotReinstalledWhileUnchanged() {
        store.undecodable = true
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            source.load().join()
            val installs = metrics.installs.size

            source.revalidate().join()
            source.revalidate().join()

            metrics.installs.assert().hasSize(installs)
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun confirmationUsesTheTimeTheReadStarted() {
        val clock = ManualClock()
        testSource(store, clock = clock).use { source ->
            source.load().join()
            store.hold = true
            val startedAt = clock.now.plusSeconds(5)
            clock.advance(Duration.ofSeconds(5))
            val revalidation = source.revalidate()
            clock.advance(Duration.ofSeconds(10))
            store.releaseAll()
            revalidation.join()

            source.peek()!!.policiesEntry.confirmedAt.assert().isEqualTo(startedAt)
            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ofSeconds(10))
        }
    }
}
