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

/**
 * What a client sends to either version and sees from it during a rolling upgrade: the `CoSec-*` parameters read alike,
 * one stated twice in disagreement refused by both (MIX-21); every problem the previous version answers, with its
 * status and type, answered alike by the current one, which may add others (MIX-18; `contract/` pins the wire within one
 * version).
 */
class EdgeCompatTest {
    private val requests = listOf(
        "CoSec-Space-Id=team-a\nCoSec-Device-Id=d1",
        "",
        "CoSec-Space-Id=team-a\nCoSec-Space-Id=team-a",
        "CoSec-Space-Id=team-a\nCoSec-Space-Id=team-b",
        "CoSec-Space-Id= \nCoSec-Device-Id=d1",
    )

    @Test
    fun `the CoSec parameters are read alike, and an ambiguous one refused, by both versions`() {
        for (headers in requests) {
            Side.PREVIOUS("parameters.read", "headers" to headers).assert().describedAs(headers)
                .isEqualTo(Side.CURRENT("parameters.read", "headers" to headers))
        }
        Side.CURRENT("parameters.read", "headers" to requests[3]).assert().isEqualTo("ambiguous")
    }

    /** A later version may add a problem; none the previous one answers is gone or changed (status, type, title). */
    @Test
    fun `every problem the previous version answers a client with, the current one answers alike`() {
        val current = Side.CURRENT("problems").lines()
        Side.PREVIOUS("problems").lines().assert().isNotEmpty().allMatch { it in current }
    }
}
