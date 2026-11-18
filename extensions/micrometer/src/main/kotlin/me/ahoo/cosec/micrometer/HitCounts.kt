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

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.LongAdder

/**
 * The `cosec.snapshot.loads{result=hit}` counts, one per kind, taken at every `peek` on the request's thread.
 *
 * A shared counter costs every peek an atomic instruction, and peeks on several threads contend for its cache lines
 * (#1614: 87.6 % of a peek's throughput on one thread, 54.8 % on eight). Here a platform thread counts in cells only it
 * writes, with a plain increment published as an opaque write, and a scrape adds the cells up. A virtual thread counts
 * in a shared adder instead: it usually lives for one request, and cells of its own would cost a request an allocation
 * and grow without bound. The cells of a thread that has ended are folded into [retired] when a thread first counts or
 * a scrape reads, so they number at most the live threads that counted.
 */
internal class HitCounts(private val kinds: Int) {
    private class Cells(owner: Thread, kinds: Int) {
        val owner = WeakReference(owner)
        val counts = LongArray(kinds)

        /** Whether its thread has ended: its writes happen-before this answer (JLS 17.4.5). */
        fun ended(): Boolean = owner.get()?.isAlive != true
    }

    private val lock = Any()

    /** The cells of the threads that count, guarded by [lock]. */
    private val cells = ArrayList<Cells>()

    /** What threads that ended had counted, guarded by [lock]. */
    private val retired = LongArray(kinds)
    private val shared = Array(kinds) { LongAdder() }
    private val local = ThreadLocal.withInitial { register(Thread.currentThread()) }

    fun increment(kind: Int) {
        if (Thread.currentThread().isVirtual) {
            shared[kind].increment()
            return
        }
        val counts = local.get().counts
        // One writer per cell: a plain read and an opaque write lose nothing, and a scrape never sees a torn value.
        COUNT.setOpaque(counts, kind, counts[kind] + 1)
    }

    /** The hits of [kind] so far: never less than the scrape before. */
    fun count(kind: Int): Double = synchronized(lock) {
        fold()
        var total = retired[kind] + shared[kind].sum()
        for (cell in cells) {
            total += COUNT.getOpaque(cell.counts, kind) as Long
        }
        total.toDouble()
    }

    private fun register(thread: Thread): Cells = synchronized(lock) {
        fold()
        Cells(thread, kinds).also(cells::add)
    }

    private fun fold() {
        cells.removeIf { cell ->
            cell.ended().also { ended ->
                if (ended) {
                    for (kind in 0 until kinds) {
                        retired[kind] += cell.counts[kind]
                    }
                }
            }
        }
    }

    private companion object {
        val COUNT: VarHandle = MethodHandles.arrayElementVarHandle(LongArray::class.java)
    }
}
