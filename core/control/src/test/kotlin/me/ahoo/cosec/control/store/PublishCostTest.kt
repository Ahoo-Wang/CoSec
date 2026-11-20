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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.StoreFixtures.binding
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * `CTL-44`: what a tenant's publish reads and writes does not depend on how many other tenants refer to the same
 * policies, for a reference it adds and for one it drops alike. Each store call is counted, with the entries it reads
 * or writes (the members of a back-reference set, the entries of a revision index), on stores where 10 and where 400
 * other tenants already refer to the platform's policy.
 */
class PublishCostTest {
    /** Calls by operation, and the entries each read or wrote, summed. */
    private data class Cost(val calls: Map<String, Int>, val entries: Map<String, Int>)

    private class Counting(target: ControlStore) {
        val calls = LinkedHashMap<String, Int>()
        val entries = LinkedHashMap<String, Int>()
        var counting = false

        val store: ControlStore = Proxy.newProxyInstance(
            ControlStore::class.java.classLoader,
            arrayOf(ControlStore::class.java),
        ) { _, method, args ->
            val result = try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (counting) {
                // A function over a value class is mangled (addReferrer-<hash>): counted under its name.
                val name = method.name.substringBefore('-')
                calls.merge(name, 1, Int::plus)
                entries.merge(name, sizeOf(args.orEmpty().toList() + result), Int::plus)
            }
            result
        } as ControlStore

        fun cost(): Cost = Cost(calls.toMap(), entries.toMap())

        private fun sizeOf(values: List<Any?>): Int = values.sumOf { value ->
            when (value) {
                is RevisionIndex -> value.entries.size + value.pending.size + value.indexed.size + value.reclaiming.size
                is Versioned<*> -> sizeOf(listOf(value.value))
                is ReferrerEntry -> 1
                is Collection<*> -> value.size
                else -> 0
            }
        }
    }

    private fun costOf(others: Int, drop: Boolean): Cost = runBlocking {
        val clock = MutableClock()
        val counting = Counting(InMemoryControlStore(clock))
        val revisions = Revisions(counting.store, clock)
        revisions.publish(
            SnapshotKey.Platform,
            null,
            candidate(SnapshotKey.Platform, listOf(policy("shared"))),
            publication()
        )
        val bound = candidate(SnapshotKey.Platform, bindings = listOf(binding("b", "shared")))
        repeat(others) {
            val tenant = SnapshotKey.Tenant("t$it")
            revisions.publish(tenant, null, candidate(tenant, bindings = bound.bindings), publication())
        }
        val acme = StoreFixtures.ACME
        val first = candidate(acme, bindings = if (drop) bound.bindings else emptyList())
        revisions.publish(acme, null, first, publication("c1"))
        counting.counting = true
        val next = candidate(acme, bindings = if (drop) emptyList() else bound.bindings)
        revisions.publish(acme, revisions.head(acme), next, publication("c2"))
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        counting.cost()
    }

    @Test
    fun `a tenant's publish that adds a reference costs the same at 10 and at 400 referrers`() {
        val few = costOf(10, drop = false)
        few.calls.getValue("addReferrer").assert().isEqualTo(1)
        few.calls["referrers"].assert().isNull()
        costOf(400, drop = false).assert().isEqualTo(few)
    }

    @Test
    fun `a tenant's publish that drops a reference costs the same at 10 and at 400 referrers`() {
        val few = costOf(10, drop = true)
        few.entries.getValue("removeReferrers").assert().isEqualTo(1)
        few.calls["referrers"].assert().isNull()
        costOf(400, drop = true).assert().isEqualTo(few)
    }

    /** R2C-8: what a repair of one document costs, at [others] other referrers of the same policy, its record kept or not. */
    private fun repairCost(others: Int, recorded: Boolean): Cost = runBlocking {
        val clock = MutableClock()
        val memory = InMemoryControlStore(clock)
        val counting = Counting(memory)
        val revisions = Revisions(counting.store, clock)
        revisions.publish(
            SnapshotKey.Platform,
            null,
            candidate(SnapshotKey.Platform, listOf(policy("shared"))),
            publication()
        )
        val bound = candidate(SnapshotKey.Platform, bindings = listOf(binding("b", "shared"))).bindings
        repeat(others) {
            val tenant = SnapshotKey.Tenant("t\$it")
            revisions.publish(tenant, null, candidate(tenant, bindings = bound), publication())
        }
        val acme = StoreFixtures.ACME
        revisions.publish(acme, null, candidate(acme, bindings = bound), publication())
        if (!recorded) {
            val index = memory.index(acme)!!
            memory.putIndex(acme, index.digest, index.value.copy(indexed = emptyList()))
        }
        counting.counting = true
        revisions.repairReferences(acme).assert().isEqualTo(if (recorded) 0 else 1)
        counting.cost()
    }

    @Test
    fun `a repair reads no back-reference set, whatever the number of referrers`() {
        val recorded = repairCost(10, recorded = true)
        recorded.calls["referrers"].assert().isNull()
        recorded.calls["addReferrer"].assert().isNull()
        repairCost(400, recorded = true).assert().isEqualTo(recorded)
        val unrecorded = repairCost(10, recorded = false)
        unrecorded.calls["referrers"].assert().isNull()
        unrecorded.calls.getValue("addReferrer").assert().isEqualTo(1)
        repairCost(400, recorded = false).assert().isEqualTo(unrecorded)
    }
}
