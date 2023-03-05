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

package me.ahoo.cosec.api.context

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class AttributesTest {

    @Test
    fun mergeAttributes() {
        val attributes = MockAttributes().mergeAttributes(additionalAttributes = mapOf("a" to "a"))
        assertThat(attributes.attributes["a"], equalTo("a"))
        assertThat(attributes, equalTo(attributes.mergeAttributes(mapOf())))

        val attributes2 = attributes.mergeAttributes(additionalAttributes = mapOf("b" to "b"))
        assertThat(attributes2.attributes["a"], equalTo("a"))
        assertThat(attributes2.attributes["b"], equalTo("b"))
    }

    data class MockAttributes(override val attributes: Map<String, String> = mapOf()) :
        Attributes<MockAttributes, String, String> {
        override fun withAttributes(attributes: Map<String, String>): MockAttributes {
            return copy(attributes = attributes)
        }
    }
}