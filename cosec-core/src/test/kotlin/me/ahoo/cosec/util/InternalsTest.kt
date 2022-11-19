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
package me.ahoo.cosec.util

import me.ahoo.cosec.util.Internals.format
import me.ahoo.cosec.util.Internals.isInternal
import me.ahoo.cosec.util.Internals.parseRaw
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * InternalIdsTest .
 *
 * @author ahoo wang
 */
internal class InternalsTest {
    @Test
    fun compare() {
        Assertions.assertTrue('(' < '0')
        Assertions.assertTrue(')' < '0')
    }

    @Test
    fun format() {
        assertThat(format("id"), equalTo("(id)"))
    }

    @Test
    fun parseRawId() {
        assertThat(parseRaw("(id)"), equalTo("id"))
        Assertions.assertEquals("id", parseRaw("(id)"))
    }

    @Test
    fun isInternal() {
        assertThat(isInternal("(id)"), equalTo(true))
        assertThat(isInternal("(i)"), equalTo(true))
        assertThat(isInternal("()"), equalTo(false))
        assertThat(isInternal("("), equalTo(false))
    }
}
