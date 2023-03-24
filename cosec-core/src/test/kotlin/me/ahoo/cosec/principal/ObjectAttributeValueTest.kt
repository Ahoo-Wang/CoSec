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

package me.ahoo.cosec.principal

import me.ahoo.cosec.principal.ObjectAttributeValue.Companion.asAttributeValue
import org.junit.jupiter.api.Test

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

class ObjectAttributeValueTest {

    @Test
    fun duplicateAsAttributeValue() {
        val attributeValue = "value".asAttributeValue()
        assertThat(attributeValue, sameInstance(attributeValue.asAttributeValue()))
    }

    @Test
    fun stringAsAttributeValue() {
        val value = "1"
        val attributeValue = value.asAttributeValue()
        assertThat(attributeValue, instanceOf(TextAttributeValue::class.java))
        assertThat(attributeValue.value, equalTo(value))
        assertThat(attributeValue.asString(), equalTo(value))
        assertThat(attributeValue.asObject(Int::class.java), equalTo(1))
    }

    @Test
    fun objectAsAttributeValue() {
        val value = this
        val attributeValue = value.asAttributeValue()
        assertThat(attributeValue, instanceOf(ObjectAttributeValue::class.java))
        assertThat(attributeValue.value, equalTo(value))
        assertThat(attributeValue.asString(), equalTo("{}"))
        assertThat(attributeValue.asObject(ObjectAttributeValueTest::class.java), equalTo(this))
    }
}