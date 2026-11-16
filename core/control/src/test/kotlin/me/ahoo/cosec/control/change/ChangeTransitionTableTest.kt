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
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.change.ChangeState.ABANDONED
import me.ahoo.cosec.control.change.ChangeState.AWAITING_APPROVAL
import me.ahoo.cosec.control.change.ChangeState.CONFLICTED
import me.ahoo.cosec.control.change.ChangeState.EXPIRED
import me.ahoo.cosec.control.change.ChangeState.PUBLISHED
import me.ahoo.cosec.control.change.ChangeState.READY
import me.ahoo.cosec.control.change.ChangeState.REJECTED
import me.ahoo.cosec.control.change.ChangeState.SHADOWING
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ChangeStep
import me.ahoo.cosec.control.lifecycle.StagingSlots
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The change flow as it is today (RFC 0013 B4, PR 7): for each command, the states it moves a change from and the states
 * it leaves it in, pinned for every state there is. A change is put in each state directly, and the command is run by
 * an operator every decision permits, so the only refusal that depends on the state is the state's own (409,
 * `is <state>, not one of ...`): a command refuses exactly the states outside its from-set, and what it does not refuse
 * either fails for a reason of its own or leaves the change in one of its to-states.
 */
class ChangeTransitionTableTest {
    /**
     * A command, its own row, and the other steps it may take on its way. It accepts its own row's From, and ends in
     * any To of the steps it takes: a publish ends `PUBLISHED` through [ChangeStep.WRITTEN], a stage that does not get
     * its slot `CHECKED` through [ChangeStep.UNRESERVE]. Which steps a command takes is a claim about several rows, so
     * it is made here, in the test, and not in the table.
     */
    private class Command(
        val step: ChangeStep,
        val also: Set<ChangeStep> = emptySet(),
        val run: suspend (ChangeHarness, ChangeId) -> ChangeRequest,
    ) {
        val from: Set<ChangeState> get() = step.from
        val to: Set<ChangeState> get() = (also + step).flatMapTo(HashSet()) { it.to }
    }

    /** Each command and its row of the table ([ChangeStep]): the code is held to the table it declares. */
    private val table = linkedMapOf(
        "rebase" to Command(ChangeStep.REBASE) { h, id -> h.changes.rebase(ALICE_OP, id) },
        "stage" to Command(ChangeStep.STAGE, setOf(ChangeStep.UNRESERVE)) { h, id -> h.changes.stage(ALICE_OP, id) },
        "skipShadow" to Command(ChangeStep.SKIP_SHADOW, setOf(ChangeStep.UNRESERVE)) { h, id ->
            h.changes.skipShadow(ALICE_OP, id, "hurry")
        },
        "shadowed" to Command(ChangeStep.SHADOWED) { h, id ->
            h.changes.shadowed(id)
        },
        "approve" to Command(ChangeStep.APPROVE) { h, id ->
            // A person of the platform with a credential, as an approval records it, approving the candidate it has.
            val approver = EngineAuthority(h.revisions).operator(TenantId.PLATFORM, "pia")
            h.changes.approve(approver, id, checkNotNull(h.changes.get(id).candidate))
        },
        "reject" to Command(ChangeStep.REJECT) { h, id -> h.changes.reject(PLATO, id, "no") },
        "abandon" to Command(ChangeStep.ABANDON) { h, id -> h.changes.abandon(ALICE_OP, id) },
        "publish" to Command(ChangeStep.PUBLISH, setOf(ChangeStep.UNWRITTEN, ChangeStep.WRITTEN)) { h, id ->
            h.changes.publish(ALICE_OP, id)
        },
    )

    /**
     * A change of tenant `acme` put in [state], with a window when it is a staged one: open while `SHADOWING`, closed
     * past it, with nothing to acknowledge and nothing blocking.
     */
    private fun changeIn(state: ChangeState): Pair<ChangeHarness, ChangeId> = runBlocking {
        val harness = ChangeHarness()
        val proposed = harness.changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("p"))), "t")
        val stored = checkNotNull(harness.memory.change(proposed.id))
        val now = harness.clock.now
        val closed = ShadowSummary(emptyMap(), now, insufficient = false, blocked = false)
        val window = if (state == SHADOWING) ShadowState("w", now) else ShadowState("w", now, closed)
        val forced = stored.value.copy(state = state, shadow = if (state.staged) window else null)
        check(harness.memory.putChange(stored.digest, forced))
        harness to proposed.id
    }

    /** What [command] did to a change in [state]: the state's own refusal, another failure, or the state it left. */
    private sealed interface Outcome {
        object RefusedForItsState : Outcome
        data class Failed(val error: Throwable) : Outcome
        data class Moved(val to: ChangeState) : Outcome
    }

    private fun outcome(command: Command, state: ChangeState): Outcome {
        val (harness, id) = changeIn(state)
        return try {
            Outcome.Moved(runBlocking { command.run(harness, id) }.state)
        } catch (e: ChangeRejectedException) {
            if (e.status == CONFLICT && e.message.orEmpty().contains("is $state, not one of")) {
                Outcome.RefusedForItsState
            } else {
                Outcome.Failed(e)
            }
        } catch (e: IllegalStateException) {
            Outcome.Failed(e)
        }
    }

    @Test
    fun `each command refuses exactly the states outside its from-set, for every state`() {
        val wrong = ArrayList<String>()
        for ((name, command) in table) {
            for (state in ChangeState.entries) {
                val refused = outcome(command, state) == Outcome.RefusedForItsState
                if (refused != (state !in command.from)) {
                    wrong += "$name from $state: ${if (refused) "refused" else "not refused"}"
                }
            }
        }
        wrong.assert().isEmpty()
    }

    @Test
    fun `each command leaves a change it moves in one of its to-states`() {
        val wrong = ArrayList<String>()
        val moved = ArrayList<String>()
        for ((name, command) in table) {
            for (state in command.from) {
                val outcome = outcome(command, state)
                if (outcome is Outcome.Moved) {
                    moved += "$name $state -> ${outcome.to}"
                    if (outcome.to !in command.to) wrong += "$name $state -> ${outcome.to}"
                }
            }
        }
        wrong.assert().isEmpty()
        // Every command moves a change from at least one state of its from-set in this setup.
        table.keys.filterNot { name -> moved.any { it.startsWith("$name ") } }.assert().isEmpty()
    }

    @Test
    fun `a change past its expiry is expired from every state that is not terminal, and only from those`() {
        for (state in ChangeState.entries) {
            val (harness, id) = changeIn(state)
            harness.clock.now = harness.clock.now.plus(Duration.ofDays(8))
            val read = runBlocking { harness.changes.get(id) }.state
            read.assert().describedAs("$state").isEqualTo(if (state.terminal) state else EXPIRED)
        }
    }

    @Test
    fun `the terminal states are published, rejected, abandoned and expired`() {
        ChangeState.entries.filter { it.terminal }.assert().containsExactlyInAnyOrder(
            PUBLISHED,
            REJECTED,
            ABANDONED,
            EXPIRED
        )
        CONFLICTED.terminal.assert().isFalse()
    }

    /**
     * A step that would leave a change outside its row's To, or that names a From wider than its row, is a fault: the
     * transition throws, and nothing is written.
     */
    @Test
    fun `no step writes a change outside its row`() {
        val (harness, id) = changeIn(AWAITING_APPROVAL)
        val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
        val flow = ChangeLifecycle(
            harness.memory,
            harness.clock,
            options,
            StagingSlots(harness.memory, harness.clock, options),
            ChangeListeners.NONE,
            ChangeAuthority(PERMIT_ALL, options),
        )
        val outside = runCatching {
            runBlocking {
                flow.transition(
                    id,
                    ChangeStep.APPROVE
                ) { it.copy(state = PUBLISHED) }
            }
        }
        outside.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        val wider = runCatching {
            runBlocking { flow.transition(id, ChangeStep.APPROVE, setOf(AWAITING_APPROVAL, READY)) { it } }
        }
        wider.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        runBlocking { harness.changes.get(id) }.state.assert().isEqualTo(AWAITING_APPROVAL)
    }

    /**
     * The table itself, pinned: [ChangeStep] rendered as markdown equals `change-transitions.md`. A transition changes
     * only with that file, in review; RFC 0007 §5.5 and §6.1 copy from it.
     */
    @Test
    fun `the transition table is the one committed in change-transitions md`() {
        fun states(states: Set<ChangeState>) = ChangeState.entries.filter { it in states }.joinToString(", ")
        val rendered = buildString {
            appendLine("| Step | From | To |")
            appendLine("|---|---|---|")
            ChangeStep.entries.forEach { appendLine("| ${it.name} | ${states(it.from)} | ${states(it.to)} |") }
        }
        val committed = checkNotNull(javaClass.getResource("/change-transitions.md")).readText()
        committed.substringAfter("<!-- table -->\n").substringBefore("<!-- end -->").assert().isEqualTo(rendered)
    }

    private companion object {
        const val CONFLICT = 409
    }
}
