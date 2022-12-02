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
package me.ahoo.cosec.internal

import me.ahoo.cosec.api.internal.InternalIds.isWrapped
import me.ahoo.cosec.api.internal.InternalIds.unwrap
import me.ahoo.cosec.api.internal.InternalIds.wrap
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * InternalIdsTest .
 *
 * @author ahoo wang
 */
internal class InternalIdsTest {
    @Test
    fun compare() {
        Assertions.assertTrue('(' < '0')
        Assertions.assertTrue(')' < '0')
    }

    @Test
    fun format() {
        assertThat(wrap("id"), equalTo("(id)"))
    }

    @Test
    fun parseRawId() {
        assertThat(unwrap("(id)"), equalTo("id"))
        Assertions.assertEquals("id", unwrap("(id)"))
    }

    @Test
    fun isInternal() {
        assertThat(isWrapped("(id)"), equalTo(true))
        assertThat(isWrapped("(i)"), equalTo(true))
        assertThat(isWrapped("()"), equalTo(false))
        assertThat(isWrapped("("), equalTo(false))
    }
}
