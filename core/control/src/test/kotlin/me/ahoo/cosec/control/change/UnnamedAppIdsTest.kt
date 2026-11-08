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
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * SCP-10: the document format takes any binding app but `*`, but an app the app id rule refuses has no key
 * (`SnapshotKey.App`) and no request is ever in it. The control plane refuses such a binding at validation, and
 * whatever it reads that names one is a format error: nothing skips it.
 */
class UnnamedAppIdsTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes
    private val refused = listOf(" ", "a\u0001b", "x".repeat(129))

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    private fun holding(app: String): PolicyDocument = StoreFixtures.candidate(
        ACME,
        emptyList(),
        listOf(bind("odd", "shared", app), bind("ok", "shared", "order"))
    )

    /** acme's head as a build before the app id rule left it, written past every check: nothing writes one now. */
    private suspend fun storedPastEveryCheck(document: PolicyDocument) {
        val base = harness.revisions.head(ACME)
        val next = PolicyDocument(
            document.kind,
            document.owner,
            (base?.revision ?: 0) + 1,
            document.publishedAt,
            document.policies,
            document.bindings,
        )
        val stored = SnapshotDocument(PolicyDocumentCodec.SCHEMA, next.revision, next.publishedAt, PolicyDocumentCodec.encode(next))
        harness.memory.putRecord(ACME, stored)
        harness.memory.putHead(ACME, base?.digest, stored)
    }

    @Test
    fun `a binding in an app no request can be in is refused with 400, naming it`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        for (app in refused) {
            val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutBinding(bind("b", "shared", app))), "t")
            val finding = change.errors.single()
            finding.check.assert().isEqualTo("V2")
            finding.path.assert().isEqualTo("$.bindings[0].app")
            finding.status.assert().isEqualTo(400)
            finding.message.assert().contains("binding [b]").contains("cannot be an app id")
                .doesNotContain("\u0001")
            change.state.assert().isEqualTo(ChangeState.DRAFT)
        }
        // A space inside, or a letter outside ASCII, is an app id.
        for (app in listOf("order app", "ｏrder")) {
            changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutBinding(bind("b", "shared", app))), "t")
                .errors.assert().describedAs(app).isEmpty()
        }
    }

    @Test
    fun `a head that holds one is a format error, so neither a publish nor a repair gets past it`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        for (app in refused) {
            // Not even the lowest write path takes one.
            val before = harness.revisions.head(ACME)
            assertThrownBy<SnapshotFormatException> {
                runBlocking { harness.revisions.publish(ACME, before, holding(app), StoreFixtures.publication()) }
            }.hasMessageContaining("binding [odd]").hasMessageNotContaining("\u0001")
            harness.revisions.head(ACME)?.revision.assert().isEqualTo(before?.revision)
        }
        storedPastEveryCheck(holding(refused.first()))
        val head = harness.revisions.head(ACME)!!.revision
        assertThrownBy<SnapshotFormatException> { runBlocking { harness.revisions.repairReferences(ACME) } }
        val dropped = changes.propose(ALICE_OP, ACME, head, listOf(Operation.RemoveBinding(BindingId("odd"))), "t")
        dropped.errors.assert().isEmpty()
        changes.skipShadow(ALICE_OP, dropped.id, "test")
        assertThrownBy<SnapshotFormatException> { runBlocking { changes.publish(ALICE_OP, dropped.id) } }
        harness.revisions.head(ACME)!!.revision.assert().isEqualTo(head)
    }

    @Test
    fun `a stored change of such an app is a format error`() {
        val stored = ChangeRequestCodec.encode(
            changes.let {
                runBlocking { it.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("p"))), "t") }
            }
        ).decodeToString()
        // Of such an app: a format error, and the stored key is escaped in its message.
        val ofApp = stored.replace("\"key\":\"tenant:acme\"", "\"key\":\"app:a\\u0001b\"")
        assertThrownBy<SnapshotFormatException> { ChangeRequestCodec.decode(ofApp.encodeToByteArray()) }
            .hasMessageNotContaining("\u0001")
        val malformed = stored.replace("\"key\":\"tenant:acme\"", "\"key\":\"app:a:b\"")
        assertThrownBy<SnapshotFormatException> { ChangeRequestCodec.decode(malformed.encodeToByteArray()) }
        // Checked with the head of such an app: the same.
        val checked = stored.replace("\"checkedWith\":{", "\"checkedWith\":{\"app: \":null,")
        checked.assert().isNotEqualTo(stored)
        assertThrownBy<SnapshotFormatException> { ChangeRequestCodec.decode(checked.encodeToByteArray()) }
    }
}
