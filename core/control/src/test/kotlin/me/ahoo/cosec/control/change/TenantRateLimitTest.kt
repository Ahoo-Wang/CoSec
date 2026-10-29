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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tenant's own rate limits (RFC 0006 §8.1, OBL-2): they count in its own buckets, so a tenant administrator tightens or
 * loosens them with `cosec:write` alone, never needing a second person, and can never touch the platform's or an app's.
 */
class TenantRateLimitTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val ids = AtomicInteger()

    // Four-eyes: every change but the trust root's own would otherwise be analyzed for escalations.
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
    )

    private fun rule(id: String, permits: Double) =
        RateLimitSource(id, StatementSource(id, Effect.DENY, listOf("*")), key = null, limit = RateLimitSpec(permits))

    @Test
    fun `a tenant tightens and loosens its own limits without a second person`() = runBlocking<Unit> {
        listOf(rule("api", 5.0), rule("api", 5000.0)).forEach { limit ->
            val base = revisions.head(ACME)?.revision ?: 0
            val change = changes.propose(ALICE_OP, ACME, base, listOf(Operation.PutRateLimit(limit)), "limit")
            change.state.assert().isEqualTo(ChangeState.CHECKED)
            change.escalations.assert().isEmpty()
            changes.skipShadow(ALICE_OP, change.id, "test")
            changes.publish(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        }
    }

    @Test
    fun `a tenant administrator cannot touch the platform's or an app's rules`() = runBlocking<Unit> {
        listOf(SnapshotKey.Platform, SnapshotKey.App("order")).forEach { key ->
            val refused = try {
                changes.propose(ALICE_OP, key, 0, listOf(Operation.PutRateLimit(rule("api", 1.0))), "limit")
                null
            } catch (e: ChangeRejectedException) {
                e.status
            }
            refused.assert().isEqualTo(403)
        }
    }
}
