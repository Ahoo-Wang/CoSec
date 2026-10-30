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

package me.ahoo.cosec.engine.flat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `subject.attributes.<name>` as the engine, and the grant analysis with it, read it (RC1-2). */
class SubjectAttributesTest {
    private val attributes: Map<String, Any> = mapOf(
        "region" to "eu",
        "suspended" to true,
        "level" to 3,
        "score" to 1.5,
        "teams" to listOf("red", "blue"),
    )

    @Test
    fun `a value of any type reads as its toString`() {
        SubjectAttributes.string(attributes, "region").assert().isEqualTo("eu")
        SubjectAttributes.string(attributes, "suspended").assert().isEqualTo("true")
        SubjectAttributes.string(attributes, "level").assert().isEqualTo("3")
        SubjectAttributes.string(attributes, "score").assert().isEqualTo("1.5")
        SubjectAttributes.string(attributes, "teams").assert().isEqualTo("[red, blue]")
    }

    @Test
    fun `an attribute the subject does not have is absent`() {
        SubjectAttributes.string(attributes, "missing").assert().isNull()
        SubjectAttributes.string(emptyMap(), "region").assert().isNull()
    }
}
