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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant

/** `verify-chain` over the control plane's in-memory store: what it reports, its exit code, and that it writes nothing. */
class ChainVerificationTest {
    private val memory = InMemoryControlStore()
    private val revisions = Revisions(memory)
    private val acme = SnapshotKey.Tenant("acme")
    private val order = SnapshotKey.App("order")

    @Test
    fun `an intact chain is ok, down to the first revision`() {
        publish(acme, 3)
        publish(order, 1)
        val (code, output) = verify()
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.lines().filter { it.isNotEmpty() }.assert().containsExactly(
            "ok app:order: intact down to revision 1",
            "ok tenant:acme: intact down to revision 1",
            "Verified 2 document(s): 0 broken, 0 could not be read.",
        )
    }

    @Test
    fun `a record a failover lost breaks the chain at its revision, and the other documents are still verified`() {
        val lost = publish(acme, 3)[1]
        publish(order, 2)
        runBlocking { memory.removeRecord(acme, lost) }
        val (code, output) = verify()
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.assert()
            .contains("ok app:order: intact down to revision 1")
            .contains("BROKEN tenant:acme: no record holds revision 2 ($lost)")
            .contains("Verified 2 document(s): 1 broken, 0 could not be read.")
    }

    @Test
    fun `a head without its own record is broken at the head`() {
        val head = publish(acme, 2).last()
        runBlocking { memory.removeRecord(acme, head) }
        val (code, output) = verify()
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("BROKEN tenant:acme: no record holds revision 2 ($head)")
    }

    @Test
    fun `only the document named is verified, and one without a head is ok`() {
        publish(acme, 1)
        val lost = publish(order, 2)[0]
        runBlocking { memory.removeRecord(order, lost) }
        verify(acme).let { (code, output) ->
            code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
            output.assert().doesNotContain("app:order").contains("Verified 1 document(s)")
        }
        verify(SnapshotKey.Tenant("nobody")).let { (code, output) ->
            code.assert().isEqualTo(MigrateMain.OK)
            output.assert().contains("ok tenant:nobody: no head")
        }
    }

    @Test
    fun `a document that cannot be read is named and fails the run, after the others`() {
        publish(acme, 1)
        publish(order, 1)
        val failing = object : ControlStore by memory {
            override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? =
                if (key == order) error("redis down") else memory.index(key)
        }
        val (code, output) = verify(store = failing)
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert()
            .contains("FAILED app:order: redis down")
            .contains("ok tenant:acme: intact down to revision 1")
            .contains("Verified 2 document(s): 0 broken, 1 could not be read.")
    }

    /** Publishes [count] revisions of [key] and answers their digests, oldest first. */
    private fun publish(key: SnapshotKey, count: Int): List<SnapshotDigest> = runBlocking {
        val publication = Publication("c", ProvenanceKind.MIGRATE, Actor(TenantId.PLATFORM, PrincipalId("op")))
        List(count) {
            revisions.publish(key, revisions.head(key), document(key), publication)
            revisions.head(key)!!.digest
        }
    }

    private fun document(key: SnapshotKey): PolicyDocument {
        val (kind, owner) = ownerOf(key)
        return PolicyDocument(kind, owner, 1, Instant.now(), emptyList(), emptyList())
    }

    /** Runs the verification as the command does, over a store that fails the test on any write. */
    private fun verify(only: SnapshotKey? = null, store: ControlStore = memory): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use {
            VerifyChainCommand.report(ChainVerification(ReadOnly(store)).run(only), it)
        }
        return code to out.toString(Charsets.UTF_8)
    }

    /** [store] for reading: a write fails the test, since `verify-chain` writes nothing. */
    private class ReadOnly(store: ControlStore) : ControlStore by store {
        private fun refused(): Nothing = throw AssertionError("verify-chain wrote to the store")

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument) = refused()

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument) = refused()

        override suspend fun removeRecord(key: SnapshotKey, digest: SnapshotDigest) = refused()

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex) = refused()

        override suspend fun putReferrers(
            target: SnapshotKey,
            policy: PolicyId,
            expected: SnapshotDigest?,
            referrers: Map<SnapshotKey, Long>,
        ) = refused()

        override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest) =
            refused()

        override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot) = refused()

        override suspend fun putCandidate(key: SnapshotKey, expected: SnapshotDigest?, candidate: StoredCandidate) =
            refused()

        override suspend fun withdrawCandidate(key: SnapshotKey, window: String) = refused()

        override suspend fun putApps(expected: SnapshotDigest?, apps: AppList) = refused()

        override suspend fun acquireLease(name: String, holder: String, duration: Duration) = refused()

        override suspend fun releaseLease(name: String, holder: String) = refused()
    }
}
