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

package me.ahoo.cosec.policy.condition.part

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

internal class DeadlineCharSequenceTest {
    private fun futureDeadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    private fun pastDeadline() = System.nanoTime() - TimeUnit.SECONDS.toNanos(1)

    @Test
    fun `length delegates without checking the deadline`() {
        val sequence = DeadlineCharSequence("abc", pastDeadline())
        assertThat(sequence.length, `is`(3))
    }

    @Test
    fun `get returns the delegate char before the deadline`() {
        val sequence = DeadlineCharSequence("abc", futureDeadline())
        assertThat(sequence[1], `is`('b'))
    }

    @Test
    fun `get throws once the deadline has passed`() {
        val sequence = DeadlineCharSequence("abc", pastDeadline())
        assertThrows<RegexTimeoutException> { sequence[0] }
    }

    @Test
    fun `subSequence stays deadline-aware`() {
        val sub = DeadlineCharSequence("abcdef", pastDeadline()).subSequence(1, 4)
        assertThat(sub.length, `is`(3))
        assertThrows<RegexTimeoutException> { sub[0] }
    }

    @Test
    fun `toString returns the delegate content`() {
        val sequence = DeadlineCharSequence("abc", futureDeadline())
        assertThat(sequence.toString(), `is`("abc"))
    }
}
