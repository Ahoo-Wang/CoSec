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
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.change.ChangeState.ABANDONED
import me.ahoo.cosec.control.change.ChangeState.AWAITING_APPROVAL
import me.ahoo.cosec.control.change.ChangeState.CHECKED
import me.ahoo.cosec.control.change.ChangeState.CONFLICTED
import me.ahoo.cosec.control.change.ChangeState.DRAFT
import me.ahoo.cosec.control.change.ChangeState.EXPIRED
import me.ahoo.cosec.control.change.ChangeState.PUBLISHED
import me.ahoo.cosec.control.change.ChangeState.READY
import me.ahoo.cosec.control.change.ChangeState.REJECTED
import me.ahoo.cosec.control.change.ChangeState.SHADOWED
import me.ahoo.cosec.control.change.ChangeState.SHADOWING
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
    private class Command(
        val from: Set<ChangeState>,
        val to: Set<ChangeState>,
        val run: suspend (ChangeHarness, ChangeId) -> ChangeRequest,
    )

    private val open = ChangeState.entries.filterNot { it.terminal }.toSet()

    /** Today's table: command → (from-states, to-states). */
    private val table = linkedMapOf(
        "rebase" to Command(setOf(CONFLICTED, DRAFT, CHECKED), setOf(CHECKED, DRAFT)) { h, id -> h.changes.rebase(ALICE_OP, id) },
        "stage" to Command(setOf(CHECKED), setOf(SHADOWING, READY, AWAITING_APPROVAL)) { h, id -> h.changes.stage(ALICE_OP, id) },
        "skipShadow" to Command(setOf(CHECKED), setOf(READY, AWAITING_APPROVAL)) { h, id ->
            h.changes.skipShadow(ALICE_OP, id, "hurry")
        },
        "shadowed" to Command(setOf(SHADOWING), setOf(SHADOWING, CONFLICTED, READY, AWAITING_APPROVAL)) { h, id ->
            h.changes.shadowed(id)
        },
        "approve" to Command(setOf(AWAITING_APPROVAL), setOf(READY)) { h, id ->
            // A person of the platform with a credential, as an approval records it, approving the candidate it has.
            val approver = EngineAuthority(h.revisions).operator(TenantId.PLATFORM, "pia")
            h.changes.approve(approver, id, checkNotNull(h.changes.get(id).candidate))
        },
        "reject" to Command(setOf(AWAITING_APPROVAL), setOf(REJECTED)) { h, id -> h.changes.reject(PLATO, id, "no") },
        "abandon" to Command(open, setOf(ABANDONED)) { h, id -> h.changes.abandon(ALICE_OP, id) },
        "publish" to Command(setOf(READY), setOf(PUBLISHED, DRAFT, AWAITING_APPROVAL, CONFLICTED)) { h, id ->
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
        val window = ShadowState("w", now).let {
            if (state == SHADOWING) {
                it
            } else {
                it.copy(
                    closed = ShadowSummary(emptyMap(), now, insufficient = false, blocked = false)
                )
            }
        }
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
    fun `the terminal states are published, rejected, abandoned and expired, and no command enters SHADOWED`() {
        ChangeState.entries.filter { it.terminal }.assert().containsExactlyInAnyOrder(
            PUBLISHED,
            REJECTED,
            ABANDONED,
            EXPIRED
        )
        CONFLICTED.terminal.assert().isFalse()
        table.values.flatMap { it.to }.toSet().contains(SHADOWED).assert().isFalse()
    }

    private companion object {
        const val CONFLICT = 409
    }
}
