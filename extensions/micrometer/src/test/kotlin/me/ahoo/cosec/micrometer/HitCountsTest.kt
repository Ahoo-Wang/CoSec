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

package me.ahoo.cosec.micrometer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

class HitCountsTest {
    private val hits = HitCounts(2)

    @Test
    fun `every hit of every thread is counted, by kind`() {
        val threads = 8
        val each = 10_000
        val start = CyclicBarrier(threads)
        val workers = (0 until threads).map { t ->
            Thread.ofPlatform().start {
                start.await()
                repeat(each) { hits.increment(t % 2) }
            }
        }
        workers.forEach(Thread::join)
        hits.count(0).assert().isEqualTo((threads / 2 * each).toDouble())
        hits.count(1).assert().isEqualTo((threads / 2 * each).toDouble())
    }

    @Test
    fun `what a thread counted outlives it, and its cells are folded`() {
        repeat(3) { Thread.ofPlatform().start { repeat(5) { hits.increment(0) } }.join() }
        hits.count(0).assert().isEqualTo(15.0)
        // A new thread registers after the others ended: their counts are kept, once.
        Thread.ofPlatform().start { hits.increment(0) }.join()
        hits.count(0).assert().isEqualTo(16.0)
        hits.count(0).assert().isEqualTo(16.0)
    }

    @Test
    fun `a live thread's hits are read while it keeps counting, and never go back`() {
        val counted = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val worker = Thread.ofPlatform().start {
            repeat(100) { hits.increment(1) }
            counted.countDown()
            stop.await()
        }
        counted.await()
        hits.count(1).assert().isEqualTo(100.0)
        stop.countDown()
        worker.join()
        hits.count(1).assert().isEqualTo(100.0)
    }

    @Test
    fun `virtual threads count in the shared adder`() {
        val workers = (0 until 100).map { Thread.ofVirtual().start { hits.increment(0) } }
        workers.forEach(Thread::join)
        hits.count(0).assert().isEqualTo(100.0)
    }
}
