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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/** R2C-18: the short memory of principals the loader did not know. */
class UnknownPrincipalsTest {
    private var now = 0L

    private fun unknowns(life: Duration = Duration.ofSeconds(5), maxSize: Int = 3) =
        UnknownPrincipals(life, maxSize) { now }

    @Test
    fun `a key is unknown for its life and no longer`() {
        val unknown = unknowns()
        unknown.contains("a").assert().isFalse()
        unknown.remember("a")
        unknown.contains("a").assert().isTrue()
        now = Duration.ofSeconds(5).toNanos() - 1
        unknown.contains("a").assert().isTrue()
        now = Duration.ofSeconds(5).toNanos()
        unknown.contains("a").assert().isFalse()
    }

    @Test
    fun `forget drops a key at once`() {
        val unknown = unknowns()
        unknown.remember("a")
        unknown.forget("a")
        unknown.contains("a").assert().isFalse()
    }

    @Test
    fun `a life of zero remembers nothing`() {
        val unknown = unknowns(Duration.ZERO)
        unknown.remember("a")
        unknown.contains("a").assert().isFalse()
    }

    /** A flood of distinct principals cannot grow it: past the bound a key is not recorded, never evicting a live one. */
    @Test
    fun `the set is bounded and a full set records no more live keys`() {
        val unknown = unknowns()
        listOf("a", "b", "c", "d").forEach(unknown::remember)
        listOf("a", "b", "c").forEach { unknown.contains(it).assert().isTrue() }
        unknown.contains("d").assert().isFalse()
        // Once the live ones end, a sweep makes room.
        now = Duration.ofSeconds(6).toNanos()
        unknown.remember("d")
        unknown.contains("d").assert().isTrue()
    }
}
