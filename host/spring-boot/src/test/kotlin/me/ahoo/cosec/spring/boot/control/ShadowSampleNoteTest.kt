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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/** A shadow window that needs no samples closes on time alone: the startup says so, and says nothing when it needs some. */
class ShadowSampleNoteTest {
    @Test
    fun `zero minimum samples is named with the duration it then closes after`() {
        val shadow = ControlProperties.Shadow().apply {
            minSamples = 0
            minDuration = Duration.ofMinutes(5)
        }
        ShadowSampleNote.of(shadow).assert().isNotNull()
            .contains("cosec.control.shadow.min-samples is 0")
            .contains("PT5M")
            .contains("on time alone")
    }

    @Test
    fun `any positive minimum, the default included, is no note`() {
        ShadowSampleNote.of(ControlProperties.Shadow()).assert().isNull()
        ShadowSampleNote.of(ControlProperties.Shadow().apply { minSamples = 1 }).assert().isNull()
    }
}
