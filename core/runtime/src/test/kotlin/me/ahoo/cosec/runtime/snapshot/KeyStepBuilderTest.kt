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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The builder a thread's drains reuse (RFC 0013 B2b step 3): what it keeps between steps, and when a step changed. */
class KeyStepBuilderTest {
    private val state = KeyState<String>(retryDelay = Duration.ofMillis(100))
    private val moment = KeyMoment(Instant.EPOCH, 0, 0, false, null)
    private val builder = KeyStepBuilder(state, moment, SnapshotKey.Platform)

    private fun emitted(count: Int): List<KeyEffect<String>> {
        builder.reset(state, moment, SnapshotKey.Platform)
        repeat(count) { builder.emit(KeyEffect.DropIfEmpty) }
        return builder.effects
    }

    @Test
    fun `a step that answered a herd leaves no herd-sized list behind`() {
        val large = emitted(KeyStepBuilder.MAX_KEPT_EFFECTS + 1)
        builder.reset(state, moment, SnapshotKey.Platform)
        (builder.effects === large).assert().isFalse()
        builder.effects.assert().isEmpty()

        val kept = emitted(KeyStepBuilder.MAX_KEPT_EFFECTS)
        builder.reset(state, moment, SnapshotKey.Platform)
        (builder.effects === kept).assert().isTrue()
        builder.effects.assert().isEmpty()
    }

    @Test
    fun `an equal retry delay that is another object changes nothing`() {
        builder.reset(state, moment, SnapshotKey.Platform)
        builder.retryDelay = Duration.ofMillis(100)
        (builder.state === state).assert().isTrue()
        builder.retryDelay = Duration.ofMillis(200)
        (builder.state === state).assert().isFalse()
    }
}
