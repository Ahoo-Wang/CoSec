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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * InternalIdsTest .
 *
 * @author ahoo wang
 */
internal class InternalIdsTest {
    @Test
    fun compare() {
        ('(' < '0').assert().isTrue()
        (')' < '0').assert().isTrue()
    }

    @Test
    fun format() {
        wrap("id").assert().isEqualTo("(id)")
    }

    @Test
    fun parseRawId() {
        unwrap("(id)").assert().isEqualTo("id")
    }

    @Test
    fun isInternal() {
        isWrapped("(id)").assert().isTrue()
        isWrapped("(i)").assert().isTrue()
        isWrapped("()").assert().isFalse()
        isWrapped("(").assert().isFalse()
    }
}
