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
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** #1383 in the source itself: a constructor step that throws releases what the constructor took before it. */
class SnapshotSourceConstructionTest {
    private val inner = InMemorySnapshotDocumentStore()
    private var active = 0
    private val store = object : SnapshotDocumentStore by inner {
        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
            active++
            val subscription = inner.subscribe(listener)
            return AutoCloseable {
                active--
                subscription.close()
            }
        }
    }

    @Test
    fun `a periodic pass that cannot be scheduled releases the subscription the source took`() {
        val stopped = ScheduledThreadPoolExecutor(1).apply { shutdown() }
        val thrown = assertThrows<RejectedExecutionException> { testSource(store, scheduler = stopped) }
        thrown.assert().isNotNull()
        active.assert().describedAs("subscriptions left behind").isEqualTo(0)
    }

    @Test
    fun `a source that builds keeps its subscription until it closes`() {
        testSource(store).use { active.assert().isEqualTo(1) }
        active.assert().isEqualTo(0)
    }

    /**
     * A store may tell its listener of a change, or that notifications were lost, before `subscribe` returns. So
     * everything the listener reaches (the shared slot, the families, the passes that a reset reloads through) is built
     * before the source subscribes; a reset that found the passes unset would fail the source's construction.
     */
    @Test
    fun `a listener told of a change or a reset while it subscribes finds the source built`() {
        val calls = mapOf<String, (SnapshotChangeListener) -> Unit>(
            "reset" to { it.onReset() },
            "the shared key changed" to { it.onChanged(listOf(SnapshotKey.Platform)) },
            "a family key changed" to { it.onChanged(listOf(SnapshotKey.App("order"))) },
        )
        for ((what, call) in calls) {
            val eager = object : SnapshotDocumentStore by inner {
                override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
                    call(listener)
                    return inner.subscribe(listener)
                }
            }
            testSource(eager).use { source ->
                source.load("order").get(5, TimeUnit.SECONDS).assert().describedAs(what).isNotNull()
            }
        }
    }
}
