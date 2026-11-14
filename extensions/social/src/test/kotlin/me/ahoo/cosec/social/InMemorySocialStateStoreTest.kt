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

package me.ahoo.cosec.social

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class InMemorySocialStateStoreTest {
    private var now = 0L
    private val store = InMemorySocialStateStore(sweepFloor = 4).also { it.nanoTime = { now } }

    /** Anyone can start a sign-in: a put below the threshold never scans the store. */
    @Test
    fun `a put below the threshold scans nothing`() = runTest {
        repeat(3) { store.put("s$it", "h", Duration.ofNanos(1)) }
        now = Duration.ofMinutes(1).toNanos()

        store.sweeps.assert().isEqualTo(0)
        store.size.assert().isEqualTo(3)
    }

    /** Past the threshold the expired states go, and the next sweep waits until the store has doubled. */
    @Test
    fun `a sweep drops the expired states, and the next waits for the store to double`() = runTest {
        repeat(3) { store.put("old$it", "h", Duration.ofSeconds(1)) }
        now = Duration.ofSeconds(2).toNanos()
        store.put("live0", "h", Duration.ofMinutes(1))
        store.sweeps.assert().isEqualTo(1)
        store.size.assert().isEqualTo(1)

        // The floor holds: the next sweep comes at four states, then at eight.
        repeat(3) { store.put("live${it + 1}", "h", Duration.ofMinutes(1)) }
        store.sweeps.assert().isEqualTo(2)
        repeat(3) { store.put("more$it", "h", Duration.ofMinutes(1)) }
        store.sweeps.assert().isEqualTo(2)
        store.put("eighth", "h", Duration.ofMinutes(1))
        store.sweeps.assert().isEqualTo(3)
        store.size.assert().isEqualTo(8)
    }

    @Test
    fun `the sweep floor is positive`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { InMemorySocialStateStore(0) }
    }

    @Test
    fun `a state is taken once, and never once expired`() = runTest {
        store.put("s", "h", Duration.ofSeconds(1))
        store.put("t", "h", Duration.ofSeconds(1))
        store.take("s").assert().isEqualTo("h")
        store.take("s").assert().isNull()
        now = Duration.ofSeconds(1).toNanos()
        store.take("t").assert().isNull()
        store.take("never").assert().isNull()
    }
}
