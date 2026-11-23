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

package me.ahoo.cosec.spring.boot.authentication.social

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.RedisSocialStateStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.Properties

/** DR1-D-9: the bound comes from the memory Redis reports, else a fixed fallback; never none, never an outage. */
class SocialStateBoundTest {
    private fun info(
        vararg pairs: Pair<String, String>
    ) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun `a 64th of maxmemory, counted at the measured bytes of a sign-in`() {
        val gib = 1L shl 30
        SocialStateBound.of { info("maxmemory" to "$gib", "total_system_memory" to "${16 * gib}") }
            .assert().isEqualTo(gib / 64 / RedisSocialStateStore.STATE_BYTES)
    }

    @Test
    fun `without maxmemory, a 64th of the system's memory, and on a cluster the smallest node's`() {
        val gib = 1L shl 30
        SocialStateBound.of { info("maxmemory" to "0", "total_system_memory" to "${8 * gib}") }
            .assert().isEqualTo(8 * gib / 64 / RedisSocialStateStore.STATE_BYTES)
        SocialStateBound.of { info("10.0.0.1:6379.maxmemory" to "${2 * gib}", "10.0.0.2:6379.maxmemory" to "$gib") }
            .assert().isEqualTo(gib / 64 / RedisSocialStateStore.STATE_BYTES)
    }

    @Test
    fun `what cannot be read is the fixed fallback, never no bound`() {
        SocialStateBound.of { null }.assert().isEqualTo(SocialStateBound.FALLBACK_STATES)
        SocialStateBound.of { info("used_memory" to "1") }.assert().isEqualTo(SocialStateBound.FALLBACK_STATES)
        SocialStateBound.of {
            throw IllegalStateException(
                "NOPERM INFO"
            )
        }.assert().isEqualTo(SocialStateBound.FALLBACK_STATES)
    }
}
