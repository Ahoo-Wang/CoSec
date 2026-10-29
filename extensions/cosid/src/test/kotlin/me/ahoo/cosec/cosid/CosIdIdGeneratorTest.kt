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

package me.ahoo.cosec.cosid

import me.ahoo.cosid.runtime.CodecSpec
import me.ahoo.cosid.runtime.CosIdRuntime
import me.ahoo.cosid.runtime.GeneratorSpec
import me.ahoo.cosid.runtime.MachineSpec
import me.ahoo.cosid.runtime.RuntimeOptions
import me.ahoo.cosid.runtime.RuntimeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/** Ids from a real CosId 5 runtime: Snowflake generators on a static slot, so no store is needed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CosIdIdGeneratorTest {
    private val runtime: CosIdRuntime = CosIdRuntime.start(
        RuntimeSpec.builder("cosec-test")
            .machine(MachineSpec.Static.builder(1).build())
            .generator(
                RuntimeSpec.DEFAULT_GENERATOR,
                GeneratorSpec.Snowflake.builder().codec(listOf(CodecSpec.Radix(62, 11, true))).build(),
            )
            .generator(
                "trace",
                GeneratorSpec.Snowflake.builder()
                    .codec(listOf(CodecSpec.Radix(62, 11, true), CodecSpec.Prefix("T")))
                    .build(),
            )
            .generator(
                "order",
                GeneratorSpec.Snowflake.builder().codec(listOf(CodecSpec.Prefix("order:"))).build(),
            )
            .build(),
        RuntimeOptions.builder().build(),
    )

    @AfterAll
    fun close() {
        runtime.close()
    }

    @Test
    fun `ids are the default generator's, plain and distinct`() {
        val ids = CosIdIdGenerator.of(runtime)
        val generated = (1..10_000).map { ids.generate() }
        generated.toSet().assert().hasSize(generated.size)
        generated.filterNot { Regex("[A-Za-z0-9]{11}").matches(it) }.assert().isEmpty()
    }

    @Test
    fun `a named generator gives its ids with its codec`() {
        CosIdIdGenerator.of(runtime, "trace").generate().assert().startsWith("T").hasSize(12)
    }

    @Test
    fun `a generator whose ids are not plain fails at once`() {
        assertThrows<IllegalArgumentException> { CosIdIdGenerator.of(runtime, "order") }
    }

    @Test
    fun `an unknown generator fails at once`() {
        assertThrows<NoSuchElementException> { CosIdIdGenerator.of(runtime, "missing") }
    }
}
