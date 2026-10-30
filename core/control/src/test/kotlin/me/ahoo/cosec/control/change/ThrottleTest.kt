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

package me.ahoo.cosec.control.change

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The WARN that the app list is not to be trusted is logged at most once a minute (review of #1181, N5). */
class ThrottleTest {
    @Test
    fun `says yes at once, then once per interval`() {
        val time = TestTimeSource()
        val throttle = Throttle(1.minutes, time)
        throttle.ready().assert().isTrue()
        throttle.ready().assert().isFalse()
        time += 59.seconds
        throttle.ready().assert().isFalse()
        time += 1.seconds
        throttle.ready().assert().isTrue()
        throttle.ready().assert().isFalse()
    }
}
