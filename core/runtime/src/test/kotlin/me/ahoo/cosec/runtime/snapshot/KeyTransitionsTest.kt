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

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim
import me.ahoo.cosec.runtime.snapshot.KeyHarness.Companion.present
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * Named orders of events through [KeyTransitions] (RFC 0013 B2b, step 1): the cases the page names, each with what it
 * must answer to whom. Every step is also checked by [KeyStepChecks]; [KeyTransitionsOrderTest] tries every order.
 */
class KeyTransitionsTest {
    private val key = KeyHarness.of()

    @AfterEach
    fun noViolation() {
        key.violations.assert().isEmpty()
    }

    @Test
    fun aColdRequestStartsOneReadAndASecondJoinsIt() {
        val first = key.request(Ask.Request)
        val second = key.request(Ask.Request)
        key.reads.assert().hasSize(1)
        key.hung[second].assert().isSameAs(key.hung[first])
        val load = key.state.load!!.id
        key.offer(KeyEvent.ReadDone(load, present(1)))
        val step = key.offer(KeyEvent.Compiled(load, DocumentState.Present("c1"), 1))
        val install = step.effects.indexOfFirst { it is KeyEffect.Install }
        val answer = step.effects.indexOfFirst { it is KeyEffect.Answer }
        install.assert().isNotNegative().isLessThan(answer)
        key.answered[key.hung[first]]!!.revision.assert().isEqualTo(1L)
    }

    @Test
    fun aReloadDuringALoadIsAnsweredByTheLoadThatStartsAfterIt() {
        val request = key.request(Ask.Request)
        val firstLoad = key.state.load!!.id
        val reload = key.request(Ask.Reload(force = false))
        key.hung[reload].assert().isNotSameAs(key.hung[request])
        key.offer(KeyEvent.ReadDone(firstLoad, present(1)))
        key.offer(KeyEvent.Compiled(firstLoad, DocumentState.Present("c1"), 1))
        key.answered.keys.assert().doesNotContain(key.hung[reload])
        val secondLoad = key.state.load!!.id
        secondLoad.assert().isGreaterThan(firstLoad)
        key.offer(KeyEvent.ReadDone(secondLoad, present(2)))
        key.offer(KeyEvent.Compiled(secondLoad, DocumentState.Present("c2"), 1))
        key.answered[key.hung[request]]!!.revision.assert().isEqualTo(1L)
        key.answered[key.hung[reload]]!!.revision.assert().isEqualTo(2L)
    }

    @Test
    fun aWaiterGetsItsOwnLoadsAnswerEvenWhenALaterLoadInstallsFirst() {
        val request = key.request(Ask.Request)
        val firstLoad = key.state.load!!.id
        key.request(Ask.Reload(force = false))
        key.offer(KeyEvent.ReadDone(firstLoad, present(1)))
        key.offer(KeyEvent.Compiled(firstLoad, DocumentState.Present("c1"), 1))
        val secondLoad = key.state.load!!.id
        key.offer(KeyEvent.ReadDone(secondLoad, present(2)))
        key.offer(KeyEvent.Compiled(secondLoad, DocumentState.Present("c2"), 1))
        key.state.installed!!.revision.assert().isEqualTo(2L)
        key.answered[key.hung[request]]!!.revision.assert().isEqualTo(1L)
    }

    @Test
    fun aCompilationFromAnOlderEpochIsWithheldAndItsWaitersGetTheNextLoads() {
        val request = key.request(Ask.Request)
        val firstLoad = key.state.load!!.id
        key.offer(KeyEvent.ReadDone(firstLoad, present(1)))
        key.recompile().assert().isTrue()
        val step = key.offer(KeyEvent.Compiled(firstLoad, DocumentState.Present("old"), 1))
        step.effects.none { it is KeyEffect.Install || it is KeyEffect.Answer }.assert().isTrue()
        val secondLoad = key.state.load!!
        secondLoad.alsoAnswers.assert().contains(key.hung[request])
        key.offer(KeyEvent.ReadDone(secondLoad.id, present(1)))
        key.offer(KeyEvent.Compiled(secondLoad.id, DocumentState.Present("new"), 1))
        (key.answered[key.hung[request]]!!.state as DocumentState.Present).compiled.assert().isEqualTo("new")
    }

    @Test
    fun anEvictionWhoseClaimFailedChangesNothingAndRepliesFalse() {
        installAbsent()
        key.transitions.evictable(key.state, absent = true, onlyIfEmpty = false).assert().isTrue()
        for (claim in listOf(Claim.AT_BOUND, Claim.NOT_EVICTABLE)) {
            val before = key.state
            val reply = CompletableFuture<Boolean>()
            val step = key.offer(KeyEvent.Evict(onlyIfEmpty = false, claim, reply))
            step.state.assert().isEqualTo(before)
            reply.getNow(null).assert().isFalse()
        }
    }

    @Test
    fun aClaimedEvictionRemovesAndWhatIsQueuedBehindItFindsTheKeyRemoved() {
        installAbsent()
        val reply = CompletableFuture<Boolean>()
        key.offer(
            KeyEvent.Evict(onlyIfEmpty = false, Claim.CLAIMED, reply)
        ).effects.assert().contains(KeyEffect.Drop(claimed = true))
        reply.getNow(null).assert().isTrue()
        val request = key.request(Ask.Request)
        val pass = key.request(Ask.Load(force = false, pass = mockk()))
        val reload = key.request(Ask.Reload(force = false))
        (key.direct[request] is KeyEffect.Redispatch).assert().isTrue()
        (key.direct[pass] is KeyEffect.Skip).assert().isTrue()
        (key.direct[reload] is KeyEffect.Skip).assert().isTrue()
        key.reads.assert().isEmpty()
    }

    @Test
    fun anEvictionChecksTheKindItWasChosenForAndNeverTakesALoadingKey() {
        installAbsent()
        key.transitions.evictable(key.state, absent = false, onlyIfEmpty = false).assert().isFalse()
        key.offer(KeyEvent.Changed)
        key.transitions.evictable(key.state, absent = true, onlyIfEmpty = false).assert().isFalse()
        KeyHarness.of(shared = true).let {
            it.transitions.evictable(it.state, absent = null, onlyIfEmpty = false)
        }.assert().isFalse()
    }

    @Test
    fun aPassDoesNotJoinALoadOlderThanTheStoreReadTimeout() {
        key.request(Ask.Request)
        val pass = mockk<Pass>()
        val young = key.request(Ask.Load(force = false, pass = pass))
        key.hung[young].assert().isSameAs(key.state.load!!.handle)
        key.nanos = KeyHarness.STORE_READ_TIMEOUT.toNanos() + 1
        val stuck = key.request(Ask.Load(force = false, pass = pass))
        (key.direct[stuck] is KeyEffect.Skip).assert().isTrue()
        val request = key.request(Ask.Request)
        key.hung[request].assert().isSameAs(key.state.load!!.handle)
    }

    @Test
    fun closeAnswersTheLoadInFlightAndTheReloadBehindIt() {
        val request = key.request(Ask.Request)
        val reload = key.request(Ask.Reload(force = true))
        key.offer(KeyEvent.Close)
        key.state.load.assert().isNull()
        key.answered.keys.assert().contains(key.hung[request], key.hung[reload])
        val late = key.request(Ask.Request)
        (key.direct[late] is KeyEffect.Refuse).assert().isTrue()
    }

    @Test
    fun anAnswerAboutAnEndedOrMovedOnReadIsDropped() {
        key.request(Ask.Request)
        val load = key.state.load!!.id
        key.offer(KeyEvent.ReadDone(load, present(1)))
        val compiling = key.state
        key.offer(KeyEvent.ReadTimedOut(load)).let {
            it.state.assert().isEqualTo(compiling)
            it.effects.assert().isEmpty()
        }
        key.offer(KeyEvent.Compiled(load, DocumentState.Present("c1"), 1))
        val idle = key.state
        key.offer(KeyEvent.ReadDone(load, SnapshotRead.Absent)).state.assert().isEqualTo(idle)
    }

    @Test
    fun aRejectedCompilationKeepsWhatIsInstalledAndRetriesWithADoublingDelay() {
        installAbsent()
        val delays = mutableListOf<Duration>()
        repeat(5) {
            key.offer(KeyEvent.Changed)
            val load = key.state.load!!.id
            key.offer(KeyEvent.ReadDone(load, present(1)))
            val step = key.offer(KeyEvent.CompileRejected(load, RejectedExecutionException()))
            step.effects.none { it is KeyEffect.Install }.assert().isTrue()
            delays += step.effects.filterIsInstance<KeyEffect.ScheduleRetry>().single().delay
        }
        key.state.installed!!.origin.assert().isEqualTo(EntryOrigin.ABSENT)
        delays.map { it.toMillis() }.assert().containsExactly(100L, 200L, 400L, 800L, 1000L)
    }

    @Test
    fun aDocumentGoneAfterOneWasSeenIsInstalledAsLostAndReportedAfterTheAnswer() {
        val request = key.request(Ask.Request)
        var load = key.state.load!!.id
        key.offer(KeyEvent.ReadDone(load, present(3)))
        key.offer(KeyEvent.Compiled(load, DocumentState.Present("c3"), 1))
        key.offer(KeyEvent.Changed)
        load = key.state.load!!.id
        val step = key.offer(KeyEvent.ReadDone(load, SnapshotRead.Absent))
        key.state.installed!!.origin.assert().isEqualTo(EntryOrigin.LOST)
        val answer = step.effects.indexOfFirst { it is KeyEffect.EvictBeyond }
        val lost = step.effects.indexOfFirst { it is KeyEffect.Report }
        lost.assert().isGreaterThan(answer)
        (step.effects[lost] as KeyEffect.Report).metric.assert().isEqualTo(KeyEffect.Metric.DocumentLost(3))
        key.answered[key.hung[request]]!!.revision.assert().isEqualTo(3L)
    }

    /**
     * A key whose store holds nothing, installed as absent and idle: what a local copy answered is confirmed by a read
     * of the store that follows at once.
     */
    private fun installAbsent() {
        key.request(Ask.Request)
        key.offer(KeyEvent.ReadDone(key.state.load!!.id, SnapshotRead.Absent))
        key.state.load!!.fromLocalCopy.assert().isFalse()
        key.offer(KeyEvent.ReadDone(key.state.load!!.id, SnapshotRead.Absent))
        key.state.installed!!.origin.assert().isEqualTo(EntryOrigin.ABSENT)
        key.state.idle.assert().isTrue()
    }
}
