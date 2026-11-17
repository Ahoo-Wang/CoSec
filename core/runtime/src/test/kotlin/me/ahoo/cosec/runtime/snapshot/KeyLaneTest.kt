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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [KeyLane] alone (RFC 0013 B2b, the load lane): a take is in arrival order, and a step that throws out of it leaves the
 * events after it on the lane, for the next take, rather than losing them.
 */
class KeyLaneTest {
    /** A lane whose steps record what they take, and throw on [throwOn]. */
    private class Recording(private val throwOn: Long? = null) : KeyLane() {
        val taken = mutableListOf<Long>()

        override fun takeFromLane(event: KeyEvent.LaneEvent<*>) {
            val id = event.loadId!!
            if (id == throwOn) error("step $id threw")
            taken += id
        }

        fun offer(id: Long) = push(KeyEvent.ReadTimedOut(id))

        fun take() = takeLane()

        val waiting: Boolean get() = laneWaiting
    }

    @Test
    fun `a take is in arrival order and empties the lane`() {
        val lane = Recording()
        (1L..4L).forEach(lane::offer)
        lane.take()
        lane.taken.assert().containsExactly(1L, 2L, 3L, 4L)
        lane.waiting.assert().isFalse()
    }

    @Test
    fun `a step that throws leaves the events after it for the next take, in their order`() {
        val lane = Recording(throwOn = 2)
        (1L..4L).forEach(lane::offer)
        assertThrows<IllegalStateException> { lane.take() }
        lane.taken.assert().containsExactly(1L)
        lane.waiting.assert().isTrue()
        lane.take()
        lane.taken.assert().containsExactly(1L, 3L, 4L)
    }
}
