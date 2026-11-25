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
 * MIX-5, MIX-8 and MIX-9, the counts two versions keep on the same keys (RFC 0014 R6, T accumulation): requests split
 * between the versions never get more than one version alone would allow.
 */
class CounterCompatTest {
    private val sides = listOf(Side.CURRENT, Side.PREVIOUS)

    @Test
    fun `a rate limit taken half by each version allows no more than its quota`() {
        val prefix = prefix()
        val allowed = (0 until 20).count { i ->
            sides[i % 2]("limit.acquire", "prefix" to prefix, "rule" to "r1", "bucket" to "alice", "permits" to "0.1", "window" to "60") ==
                "allowed=true"
        }
        allowed.assert().isEqualTo(6)
    }

    @Test
    fun `sessions begun half by each version are held to the principal's limit`() {
        val prefix = prefix()
        val pushed = (1..8).flatMap { i ->
            sides[i % 2](
                "session.admit", "prefix" to prefix, "id" to "f$i", "principal" to "alice", "now" to (1_000L * i).toString(), "limit" to "4",
            ).split(',').filter { it.isNotEmpty() }
        }
        pushed.assert().containsExactly("f1", "f2", "f3", "f4")
    }

    @Test
    fun `login states are counted together, and one version takes what the other recorded`() {
        val prefix = prefix()
        val recorded = (1..6).count { i ->
            sides[i % 2]("social.put", "prefix" to prefix, "state" to "s$i", "binding" to "b$i", "max" to "4") == "ok"
        }
        recorded.assert().isEqualTo(4)
        Side.PREVIOUS("social.take", "prefix" to prefix, "state" to "s2").assert().isEqualTo("b2")
        Side.CURRENT("social.take", "prefix" to prefix, "state" to "s1").assert().isEqualTo("b1")
        Side.CURRENT("social.take", "prefix" to prefix, "state" to "s2").assert().isEqualTo("none")
    }
}
