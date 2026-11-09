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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * Two ways a snapshot source could keep serving less than the store holds, over random cases (`propertyStress` scales
 * them with `cosec.property.iterations`):
 *
 * - `SNP-7` (RC1-7): whatever mix of ids with and without a document a client asks for, and whatever the bounds, every
 *   document a family holds within its bound stays installed; entries for documents the store does not hold evict only
 *   each other, and at most `maxAbsent` of them stay.
 * - `SNP-8` (RC1-43): whatever each revision's compilation does (compile, throw, or end in a [VirtualMachineError]), the
 *   source serves the latest revision it read, compiled or failed, and never an older revision in its place.
 */
class SnapshotFailClosedPropertyTest {
    private val iterations = Integer.getInteger("cosec.property.iterations", 0)

    @Test
    fun absentEntriesNeverEvictDocuments() {
        repeat(maxOf(ABSENT_CASES, iterations / 100)) { seed -> absentCase(Random(seed)) }
    }

    @Test
    fun aFailedCompilationNeverKeepsAnOlderRevision() {
        repeat(maxOf(COMPILE_CASES, iterations / 10)) { seed -> compileCase(Random(seed)) }
    }

    private fun absentCase(random: Random) {
        val maxApps = random.nextInt(1, 5)
        val maxAbsent = random.nextInt(1, 5)
        val store = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }
        val documents = (0 until maxApps).map { SnapshotKey.App("app-$it") }
        documents.forEach { store.put(it, document(1)) }
        val absent = (0 until ABSENT_IDS).map { SnapshotKey.App("absent-$it") }
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = maxApps, maxAbsent = maxAbsent)
        testSource(store, options = options).use { source ->
            val loaded = LinkedHashSet<SnapshotKey>()
            repeat(random.nextInt(LOADS / 2, LOADS)) {
                val key = if (random.nextInt(3) == 0) documents.random(random) else absent.random(random)
                source.load((key as SnapshotKey.App).appId).join()
                if (key in documents) loaded += key
            }
            awaitCondition { absent.count(source::holds) <= maxAbsent }
            loaded.forEach { key -> source.holds(key).assert().describedAs("document $key").isTrue() }
            absent.count(source::holds).assert().isLessThanOrEqualTo(maxAbsent)
        }
    }

    private fun compileCase(random: Random) {
        val store = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }
        val compiler = OverflowingCompiler()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS)
        appSource(store, compiler, compiler, options, compileExecutor = DYING_THREAD).use { source ->
            source.load().join()
            for (revision in 2L..random.nextLong(2, MAX_REVISION)) {
                val body = when (random.nextInt(3)) {
                    0 -> "p$revision"
                    1 -> "THROW"
                    else -> OverflowingCompiler.OVERFLOW
                }
                val lost = random.nextBoolean()
                store.dropNotifications = lost
                store.put(SnapshotKey.Platform, document(revision, body))
                store.dropNotifications = false
                if (lost) {
                    // The periodic pass, which brings a lost invalidation in (SNP-6).
                    source.revalidate().join()
                }
                val entry = source.peek()!!.policiesEntry
                entry.revision.assert().describedAs("served revision").isEqualTo(revision)
                if (body == "p$revision") {
                    entry.state.assert().isInstanceOf(DocumentState.Present::class.java)
                } else {
                    entry.state.assert().describedAs(
                        "revision $revision ($body)"
                    ).isInstanceOf(DocumentState.Failed::class.java)
                }
            }
        }
    }

    /** [FakeCompiler], except that a body of [OVERFLOW] overflows the stack, as a deep document can on a small one. */
    private class OverflowingCompiler(private val fake: FakeCompiler = FakeCompiler()) : SnapshotCompiler<Compiled> {
        override fun compile(key: SnapshotKey, document: SnapshotDocument): Compiled {
            if (document.bodyBytes().decodeToString() == OVERFLOW) {
                throw StackOverflowError("a document deeper than this thread's stack")
            }
            return fake.compile(key, document)
        }

        companion object {
            const val OVERFLOW = "OVERFLOW"
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time." }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val ABSENT_CASES = 100
        const val COMPILE_CASES = 100
        const val ABSENT_IDS = 20
        const val LOADS = 40
        const val MAX_REVISION = 10L

        /**
         * Runs a compilation on the calling thread, and lets a [VirtualMachineError] end there, as it ends a pool
         * thread: the source must have installed what the compilation failed before the error leaves it.
         */
        val DYING_THREAD = Executor { task ->
            try {
                task.run()
            } catch (_: VirtualMachineError) {
                // The thread dies; a pool starts another.
            }
        }
    }
}
