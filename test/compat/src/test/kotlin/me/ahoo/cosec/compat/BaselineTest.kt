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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The order of releases the baseline is compared in, and what a case before its release must do. */
class BaselineTest {
    @Test
    fun `releases are ordered as published`() {
        val ordered = listOf(
            "8.0.0-beta.8",
            "8.0.0-beta.9",
            "8.0.0-beta.10",
            "8.0.0-rc.1",
            "8.0.0-rc.2",
            "8.0.0",
            "8.0.1",
            "8.1.0"
        )
        ordered.map(Baseline.Release::of).zipWithNext { a, b -> a < b }.assert().containsOnly(true)
    }

    @Test
    fun `a case before its release must fail, and one that already holds says so`() {
        Baseline.from("99.0.0", "a gap") { throw AssertionError("not yet") }
        assertThrows<AssertionError> { Baseline.from("99.0.0", "a case that holds") {} }
        var ran = false
        Baseline.from(Baseline.previous, "a case of the baseline") { ran = true }
        ran.assert().isTrue()
    }
}
