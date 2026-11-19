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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class EventuallyTest {
    private val bound = Bound("TEST_BOUND", Duration.ofMillis(500), "a test's own")

    @Test
    fun `returns the first answer that is done`() {
        var calls = 0
        eventually(bound, "the third call", { ++calls }) { it == 3 }.assert().isEqualTo(3)
    }

    @Test
    fun `fails naming the bound and the last answer once the deadline passes`() {
        assertThrownBy<AssertionError> {
            eventually<String>(bound, "nothing", { "still no" }) { false }
        }.hasMessageContaining("Waited TEST_BOUND [500 ms: a test's own] for nothing").hasMessageContaining("still no")
    }
}
