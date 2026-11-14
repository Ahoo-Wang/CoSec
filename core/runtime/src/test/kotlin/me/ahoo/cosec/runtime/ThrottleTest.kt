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

package me.ahoo.cosec.runtime

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class ThrottleTest {
    private var now = 0L
    private val throttle = Throttle(Duration.ofSeconds(10)) { now }

    @Test
    fun aKeyIsReportedOncePerInterval() {
        throttle.allow("a").assert().isTrue()
        now += Duration.ofSeconds(9).toNanos()
        throttle.allow("a").assert().isFalse()
        now += Duration.ofSeconds(1).toNanos()
        throttle.allow("a").assert().isTrue()
        throttle.allow("a").assert().isFalse()
    }

    @Test
    fun anotherKeyIsNotSilencedByTheFirst() {
        throttle.allow("a").assert().isTrue()
        throttle.allow("b").assert().isTrue()
        throttle.allow("a").assert().isFalse()
        throttle.allow("b").assert().isFalse()
    }
}
