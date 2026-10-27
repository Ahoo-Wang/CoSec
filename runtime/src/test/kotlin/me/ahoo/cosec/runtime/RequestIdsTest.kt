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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The client's `CoSec-Request-Id` is echoed only when it is a plain id; anything else gets a new one. */
class RequestIdsTest {
    private fun answered(id: String): String = runBlocking {
        PipelineFixtures.pipeline().evaluate(facts(headers = mapOf("CoSec-Request-Id" to listOf(id)))).requestId
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "0TEC7Hw00001zR0", "4bf92f35-77b3-4da6-a3ce-929d0e0e4736", "trace.span_1"])
    fun aPlainIdIsEchoed(id: String) {
        answered(id).assert().isEqualTo(id)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "two words", "quote\"d", "line\nbreak", "tab\t", "back\\slash", "é", "<script>", "a/b", "a;b",
            "x12345678901234567890123456789012345678901234567890123456789012345",
        ]
    )
    fun anythingElseIsReplaced(id: String) {
        answered(id).assert().isEqualTo(PipelineFixtures.GENERATED_ID)
    }
}
