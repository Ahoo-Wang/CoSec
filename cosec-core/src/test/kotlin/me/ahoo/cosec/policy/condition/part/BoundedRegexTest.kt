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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

internal class BoundedRegexTest {

    @Test
    fun `should match a legitimate input within the time budget`() {
        val regex = "192\\.168\\.0\\.[0-9]*".toRegex()
        assertThat(regex.matchesWithin("192.168.0.1", 1000), `is`(true))
    }

    @Test
    fun `should report no match for a non-matching input`() {
        val regex = "192\\.168\\.0\\.[0-9]*".toRegex()
        assertThat(regex.matchesWithin("10.0.0.1", 1000), `is`(false))
    }

    /**
     * ReDoS via super-linear backtracking: `(.*a){24}` matched against a long run of `a`s explores an
     * astronomically large search space (un-bounded, this does not complete for tens of seconds). The
     * time bound must abort it promptly with [RegexTimeoutException] — the guard fires on wall-clock
     * during backtracking, so it returns in roughly the budget regardless of the pattern's blow-up.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `should abort catastrophic backtracking with RegexTimeoutException`() {
        val evil = "(.*a){24}".toRegex()
        val malicious = "a".repeat(44)
        assertThrows<RegexTimeoutException> {
            evil.matchesWithin(malicious, 100)
        }
    }
}
