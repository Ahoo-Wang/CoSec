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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.ShadowReadings
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR1-E-6: a pass that looks at a shadow window writes what it saw only to the record of that window. The reports are
 * read through a reader that runs a store write once, while the pass looks: the record that pass then writes to is not
 * the one it read.
 */
class ShadowWindowRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val ids = AtomicInteger()
    private var whileLooking: (suspend () -> Unit)? = null
    private val reports = object : ShadowReportReader by InMemoryShadowReports(clock) {
        override suspend fun readings(window: String): ShadowReadings {
            whileLooking?.also { whileLooking = null }?.invoke()
            return super.readings(window)
        }
    }
    private val changes = Changes(
        memory,
        Revisions(memory, clock),
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        reports = reports,
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
    )

    private suspend fun shadowing(): ChangeRequest {
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("read"))), "shadowed")
        return changes.startShadow(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
    }

    /** What a write meanwhile made of the record: [edit] of the record as it is. */
    private fun meanwhile(id: ChangeId, edit: (ChangeRequest) -> ChangeRequest) {
        whileLooking = {
            val stored = memory.change(id)!!
            memory.putChange(stored.digest, edit(stored.value)).assert().isTrue()
        }
    }

    @Test
    fun `a pass that looked at a window the change was staged again past writes nothing - DR1-E-6`() =
        runBlocking<Unit> {
            val change = shadowing()
            // Its candidate lost and the change staged again: the same state, another window.
            meanwhile(change.id) { it.copy(shadow = it.shadow!!.copy(window = "${it.shadow!!.window}-again")) }

            val answered = changes.shadowed(change.id)

            val stored = memory.change(change.id)!!.value
            stored.state.assert().isEqualTo(ChangeState.SHADOWING)
            stored.shadow!!.window.assert().isEqualTo("${change.shadow!!.window}-again")
            stored.shadow!!.closed.assert().isNull()
            answered.shadow!!.window.assert().isEqualTo(stored.shadow!!.window)
        }

    @Test
    fun `a reporter another pass added meanwhile is kept, the window closed or not - CTL-56`() = runBlocking<Unit> {
        val change = shadowing()
        meanwhile(change.id) { it.copy(shadow = it.shadow!!.copy(reporters = it.shadow!!.reporters + "other")) }

        changes.shadowed(change.id)

        memory.change(change.id)!!.value.shadow!!.reporters.assert().contains("other")
    }
}
