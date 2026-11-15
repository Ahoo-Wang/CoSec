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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * #1383: a failed build holds nothing. What can refuse the configuration is checked before anything is acquired, and
 * a step that throws after that releases what the build took; the caller sees that step's own exception.
 */
class PipelineBuilderFailureTest {
    private val authenticator = Authenticator { AuthnResult.Anonymous }
    private val inner = InMemorySnapshotDocumentStore()

    /** Subscriptions the store holds: a source left behind keeps one. */
    private var active = 0
    private var subscribed = 0
    private var failSubscribe: RuntimeException? = null
    private val store = object : SnapshotDocumentStore by inner {
        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
            failSubscribe?.let { throw it }
            subscribed++
            active++
            val subscription = inner.subscribe(listener)
            return AutoCloseable {
                active--
                subscription.close()
            }
        }
    }
    private val listener = object : DecisionListener {
        override val name = "events"

        override suspend fun onDecision(event: DecisionEvent) = Unit
    }

    @Test
    fun `an invalid prewarmed id is refused before the source subscribes`() {
        assertThrows<IllegalArgumentException> {
            Pipeline.builder(authenticator, store).listener(listener).snapshots { prewarmTenants = setOf("invalid:tenant") }
                .build()
        }
        subscribed.assert().isEqualTo(0)
        active.assert().isEqualTo(0)
    }

    @Test
    fun `an invalid snapshot option is refused before anything is acquired`() {
        assertThrows<IllegalArgumentException> {
            Pipeline.builder(authenticator, store).listener(listener).snapshots { coldLoadTimeout = Duration.ZERO }
                .build()
        }
        subscribed.assert().isEqualTo(0)
    }

    @Test
    fun `an invalid listener setting is refused before the source subscribes`() {
        assertThrows<IllegalArgumentException> {
            Pipeline.builder(authenticator, store).listener(listener).listeners { queueCapacity = 0 }.build()
        }
        subscribed.assert().isEqualTo(0)
    }

    @Test
    fun `a subscription that fails releases the listeners, and the caller sees its own exception`() {
        val down = IllegalStateException("the store is down")
        failSubscribe = down
        val thrown = assertThrows<IllegalStateException> { Pipeline.builder(authenticator, store).listener(listener).build() }
        thrown.assert().isSameAs(down)
        active.assert().isEqualTo(0)
    }

    @Test
    fun `a build that succeeds holds its subscription until the pipeline closes`() {
        val pipeline = Pipeline.builder(authenticator, store).listener(listener).build()
        active.assert().isEqualTo(1)
        pipeline.close()
        active.assert().isEqualTo(0)
    }
}
