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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim

/**
 * What one step of [KeyTransitions] must keep (RFC 0013 B2b, step 1), checked by [KeyHarness] before it executes the
 * step: the order of the effects, which handle each waiter is put on, which load answers which handle, and what an
 * event about an ended load, a failure, an eviction and close may change.
 */
internal class KeyStepChecks(
    private val world: KeyWorld,
    private val before: KeyState<String>,
    private val event: KeyEvent<String>,
    private val step: KeyStep<String>,
) {
    private val after = step.state
    private val effects = step.effects
    private val closedNow = world.closed

    fun run() {
        installsBeforeAnswers()
        oneOutcomePerWaiter()
        handles()
        answers()
        reads()
        failures()
        evictions()
        shape()
    }

    /** One reply to each event that carries one; no next handle without a load; removal and close are final. */
    private fun shape() {
        val replies = effects.count { it is KeyEffect.Reply }
        val replied = if (event is KeyEvent.Recompile || event is KeyEvent.Evict) 1 else 0
        if (replies != replied) fail("$replies replies to $event")
        if (after.next != null && after.load == null) fail("a next handle with no load to complete it")
        if (before.removed && !after.removed) fail("a removed key came back")
        if (event == KeyEvent.Close && (after.load != null || after.next != null)) {
            fail(
                "close left a load or a next handle"
            )
        }
        endedLoad()
    }

    /** An event about a load that has ended changes nothing, and does nothing but log. */
    private fun endedLoad() {
        val ofLoad = event.loadId
        if (ofLoad != null && ofLoad != before.load?.id) {
            if (after != before) fail("an event about ended load $ofLoad changed the state")
            if (effects.any { it !is KeyEffect.Log }) fail("an event about ended load $ofLoad had effects: $effects")
        }
    }

    /** No waiter is answered before the entry it gets is published. */
    private fun installsBeforeAnswers() {
        val lastInstall = effects.indexOfLast { it is KeyEffect.Install }
        val firstAnswer = effects.indexOfFirst { it is KeyEffect.Answer || it is KeyEffect.Serve }
        if (lastInstall >= 0 && firstAnswer in 0 until lastInstall) fail("an answer before the install")
    }

    private fun oneOutcomePerWaiter() {
        val requested = event as? KeyEvent.Requested ?: return
        val waiter = requested.waiter ?: return
        val outcomes = effects.count {
            when (it) {
                is KeyEffect.Hang -> it.waiter === waiter
                is KeyEffect.Serve -> it.waiter === waiter
                is KeyEffect.Refuse -> it.waiter === waiter
                is KeyEffect.Skip -> it.waiter === waiter
                is KeyEffect.Redispatch -> it.event.waiter === waiter
                else -> false
            }
        }
        if (outcomes != 1) fail("$waiter got $outcomes outcomes")
        if (before.removed && effects.none {
                it is KeyEffect.Skip || it is KeyEffect.Redispatch
            }
        ) {
            fail("a removed key served $waiter")
        }
    }

    /** A request joins the load in flight or starts one; a reload hangs on a load that starts after it (`SNP-13`). */
    private fun handles() {
        val hang = effects.filterIsInstance<KeyEffect.Hang<String>>().singleOrNull() ?: return
        val ask = (event as KeyEvent.Requested).ask
        if (ask !is Ask.Reload) {
            if (hang.handle !== after.load?.handle) {
                fail(
                    "${hang.waiter} hangs on ${hang.handle}, not the load in flight"
                )
            }
            return
        }
        val old = before.load
        if (old != null && (hang.handle === old.handle || hang.handle in old.alsoAnswers)) {
            fail(
                "a reload joined the load in flight"
            )
        }
        val upcoming = if (old == null) after.load?.handle else after.next
        if (hang.handle !== upcoming) fail("a reload hangs on ${hang.handle}, not on the load that starts next")
    }

    /** Only the transition that ends a load answers, and only that load's handles, with that load's answer. */
    private fun answers() {
        val answers = effects.filterIsInstance<KeyEffect.Answer<String>>()
        if (answers.isEmpty()) return
        val ended = before.load
        if (ended == null || after.load?.id == ended.id) return fail("answered $answers with no load ending")
        val mine = listOf(ended.handle) + ended.alsoAnswers
        for (answer in answers) {
            val handle = answer.handle
            if (handle !in mine && handle !== before.next) fail("load ${ended.id} answered $handle, not its own")
            val after = world.startedAfter[handle]
            if (after != null && ended.id <= after && !closedNow) {
                fail(
                    "$handle answered by load ${ended.id}, which started before its reload"
                )
            }
        }
        val own = answers.firstOrNull { it.handle === ended.handle }?.entry
        answers.filter { it.handle in ended.alsoAnswers }.forEach {
            if (it.entry !== own) {
                fail(
                    "a withheld load's waiters got another answer than the load that compiled for them"
                )
            }
        }
    }

    private fun reads() {
        val starts = effects.filterIsInstance<KeyEffect.StartRead>()
        if (starts.size > 1) fail("two reads started at once")
        val start = starts.singleOrNull() ?: return
        val load = after.load
        if (load == null || load.id != start.loadId || load.phase != LoadPhase.Reading) {
            fail(
                "a read of $start with no such load reading"
            )
        }
        if (after.removed || closedNow) fail("a read started on a removed or closed key")
        if (effects.any { it is KeyEffect.Compile }) fail("a compile and a read in one step")
    }

    /** A failure installs nothing, but an entry that waits to be compiled again; a stale compilation is never installed (`SNP-4`). */
    private fun failures() {
        val installs = effects.filterIsInstance<KeyEffect.Install<String>>()
        val failure = event is KeyEvent.ReadTimedOut || event is KeyEvent.CompileRejected || event is KeyEvent.EffectFailed ||
            (event is KeyEvent.ReadFailed && installs.none { it.entry.origin == EntryOrigin.UNDECODABLE })
        if (failure && installs.any { it.entry.origin != EntryOrigin.RECOMPILE_PENDING }) {
            fail(
                "a failure installed $installs"
            )
        }
        val load = before.load ?: return
        if (installs.any { it.entry.origin == EntryOrigin.DOCUMENT && load.epoch < world.epoch && !world.config.shared }) {
            fail("a document compiled in epoch ${load.epoch} was installed in epoch ${world.epoch}")
        }
    }

    /** An eviction removes exactly when it was claimed, or when nothing was named and the key may go; never a loading key. */
    private fun evictions() {
        val evict = event as? KeyEvent.Evict ?: return
        val refused = evict.claim == Claim.AT_BOUND || evict.claim == Claim.NOT_EVICTABLE
        if (refused && (after != before || effects != listOf(KeyEffect.Reply(evict.reply, false)))) {
            fail("a refused eviction changed the state or had effects: $effects")
        }
        if (evict.claim == Claim.CLAIMED && (!after.removed || KeyEffect.Drop(claimed = true) !in effects)) {
            fail("a claimed eviction did not remove")
        }
        if (after.removed && !before.removed && before.load != null) fail("evicted a key with a load in flight")
    }

    private fun fail(message: String) = world.fail(message)
}

/** What [KeyStepChecks] reads of the world a key runs in, and where it reports. */
internal interface KeyWorld {
    val config: KeyConfig
    val epoch: Long
    val closed: Boolean

    /** A reload's handle must be answered by a load numbered above this: one that started after the reload. */
    val startedAfter: Map<KeyHandle<String>, Long>

    fun fail(message: String)
}
