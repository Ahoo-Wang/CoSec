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
@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.api.ControlPlaneTest.Companion.ACME
import me.ahoo.cosec.control.api.ControlPlaneTest.Companion.GLOBEX
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** `CTL-85` at the control API: what a route holds in the work budget, and what a call it refuses answers. */
class ControlWorkRoutesTest {
    private val fixture = ControlPlaneTest()
    private val alice = fixture.engine.security(ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val mallory = fixture.engine.security(GLOBEX, "mallory", roles = listOf("admin"))

    /** A budget a call of another tenant fills, so that any work asked of it would wait. */
    private fun full(): Pair<WorkBudget, CompletableDeferred<Unit>> {
        val budget = WorkBudget(capacity = 1_000, free = 0, patience = Duration.ZERO)
        val release = CompletableDeferred<Unit>()
        return budget to release
    }

    /** The fixture's plane, holding its calls' work in [budget], and keeping no document in [cache]. */
    private fun planeOver(budget: WorkBudget, cache: DocumentCache = DocumentCache()): DefaultControlPlane {
        val simulator = Simulator(cache = cache)
        val authority = ChangeAuthority(fixture.engine.authorizer, OPTIONS, revocations = RevocationReader.NONE)
        val changes = Changes(
            fixture.store,
            fixture.revisions,
            { ChangeId("w${fixture.ids.incrementAndGet()}") },
            fixture.engine.authorizer,
            fixture.clock,
            OPTIONS,
            revocations = RevocationReader.NONE,
            lockout = ControlLockout(fixture.revisions, simulator, clock = fixture.clock, work = budget),
        )
        return DefaultControlPlane(changes, fixture.revisions, authority, simulator, budget)
    }

    @Test
    fun `a call that holds all of the budget decodes a document the cache does not keep, and is answered`() =
        runBlocking<Unit> {
            val budget = WorkBudget(capacity = 1_000, free = 0, patience = Duration.ZERO)
            val plane = planeOver(budget, DocumentCache(maxHeap = 1))

            // A body heavier than the budget holds all of it, as a route's does; explain then decodes the tenant's head,
            // which this cache does not keep, in the same call: counted already, never refused for the sum.
            val explained = budget.withWork(ACME.value, 1_000_000, Duration.ZERO) {
                budget.held.assert().isEqualTo(1_000L)
                plane.explain(fixture.alice, fixture.bob("order:read"))
            }

            explained.assert().isNotNull()
            budget.refused.assert().isEqualTo(0L)
            budget.held.assert().isEqualTo(0L)
        }

    @Test
    fun `a document of another tenant is refused before any work is held or its body read`() = runBlocking<Unit> {
        val (budget, release) = full()
        val holder = launch { budget.withWork("someone", 1_000, Duration.ZERO) { release.await() } }
        yield()
        val http = ControlHttp(planeOver(budget))

        // Not JSON at all: were it read, it would be a 400; held, it would be a 503 (the budget is full).
        val response = http.handle(
            ControlHttpRequest("POST", "/documents/tenant:acme:apply", emptyMap(), "{".encodeToByteArray()),
            mallory
        )

        response.status.assert().isEqualTo(403)
        budget.refused.assert().isEqualTo(0L)
        release.complete(Unit)
        holder.join()
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `a call the budget refuses is a 503 that says when to retry`() = runBlocking<Unit> {
        val (budget, release) = full()
        val holder = launch { budget.withWork("someone", 1_000, Duration.ZERO) { release.await() } }
        yield()
        val http = ControlHttp(planeOver(budget))
        val body = """{"document":"tenant:acme","base":1,"operations":[]}""".encodeToByteArray()

        val response = http.handle(ControlHttpRequest("POST", "/changes", emptyMap(), body), alice)

        response.status.assert().isEqualTo(503)
        response.headers.assert().isEqualTo(mapOf(ControlHttp.RETRY_AFTER to "1"))
        response.body.decodeToString().assert().doesNotContain("detail")
        budget.refused.assert().isEqualTo(1L)
        release.complete(Unit)
        holder.join()
        // With room, the same call is answered as before.
        http.handle(ControlHttpRequest("POST", "/changes", emptyMap(), body), alice).status.assert().isNotEqualTo(503)
    }

    /**
     * DR2-F-2 (`CTL-85`): what reading a document the cache will not keep costs is charged to the tenant of whoever asks,
     * whichever document it is. A tenant's `explain` at the platform's and its own revisions waits in its own turn,
     * never in the platform's operators', whose own calls on the platform document are the platform's.
     */
    @Test
    fun `a tenant's explain of the platform's revisions is charged to the tenant, never to the platform - DR2-F-2`() =
        runBlocking<Unit> {
            val budget = WorkBudget(capacity = 1L shl 40, free = 0, patience = Duration.ZERO)
            val charged = CopyOnWriteArrayList<String>()
            budget.granted = { tenant, _, _, _ -> charged += tenant }
            val plane = planeOver(budget, DocumentCache(maxHeap = 1))

            budget.call { plane.explain(fixture.alice, fixture.bob("order:read"), ViewVersion(1, null, 1)) }
            charged.assert().isNotEmpty().containsOnly(ACME.value)

            charged.clear()
            budget.call { plane.explain(fixture.plato, fixture.bob("order:read")) }
            charged.assert().isNotEmpty().containsOnly(TenantId.PLATFORM.value)
        }

    private companion object {
        val OPTIONS = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    }
}
