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
import me.ahoo.cosec.control.store.UnnamedDocumentException
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * SCP-7, R2-1 of #1194's review: the document format takes any binding app but `*`, and builds before this one stored
 * them, but an app the app id rule refuses has no key (`SnapshotKey.App`) and no request is ever in it. The control
 * plane refuses such a binding at validation, and skips such an app wherever it would build a key from a document, so
 * a tenant that holds one still publishes the change that drops it.
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

    /** acme's head as an earlier build stored it: a binding of the platform's `shared` in [app], beside a valid one. */
    private suspend fun storedByAnEarlierBuild(app: String) {
        harness.revisions.publish(
            ACME,
            harness.revisions.head(ACME),
            StoreFixtures.candidate(
                ACME,
                emptyList(),
                listOf(bind("odd", "shared", app), bind("ok", "shared", "order"))
            ),
            StoreFixtures.publication(),
        )
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
                .contains("a change that only removes it is valid").doesNotContain("\u0001")
            change.state.assert().isEqualTo(ChangeState.DRAFT)
        }
        // A space inside, or a letter outside ASCII, is an app id.
        for (app in listOf("order app", "ｏrder")) {
            changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutBinding(bind("b", "shared", app))), "t")
                .errors.assert().describedAs(app).isEmpty()
        }
    }

    @Test
    fun `a head that holds one publishes the change that drops it, and no other`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        for (app in refused) {
            // An earlier build published it, and indexed what it refers to; neither publishing nor repairing throws.
            storedByAnEarlierBuild(app)
            harness.revisions.repairReferences(ACME)
            val head = harness.revisions.head(ACME)!!.revision
            // The whole candidate is checked: a change that keeps the binding is refused, whatever it changes.
            val other = changes.propose(ALICE_OP, ACME, head, listOf(Operation.PutPolicy(allow("own"))), "t")
            other.errors.map { it.path }.assert().containsExactly("$.bindings[0].app")
            // The change that only drops it is valid, and publishes: references skip an app no key can name.
            val dropped = publish(ACME, Operation.RemoveBinding(BindingId("odd")))
            dropped.state.assert().isEqualTo(ChangeState.PUBLISHED)
            harness.revisions.head(ACME)!!.document.bindings.map { it.id.value }.assert().containsExactly("ok")
            // The valid binding still refers to the platform's policy and to order's document.
            harness.revisions.referrers(SnapshotKey.Platform, me.ahoo.cosec.model.PolicyId("shared"))
                .assert().contains(ACME)
        }
    }

    @Test
    fun `a stored change of such an app is a format error of that record alone`() {
        val stored = ChangeRequestCodec.encode(
            changes.let {
                runBlocking { it.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("p"))), "t") }
            }
        ).decodeToString()
        // Of such an app: its own error, which says which key, escaped.
        val ofApp = stored.replace("\"key\":\"tenant:acme\"", "\"key\":\"app:a\\u0001b\"")
        assertThrownBy<UnnamedDocumentException> { ChangeRequestCodec.decode(ofApp.encodeToByteArray()) }
            .hasMessageContaining("app:a\\u0001b").hasMessageNotContaining("\u0001")
        // A key no build ever wrote is malformed, as before.
        val malformed = stored.replace("\"key\":\"tenant:acme\"", "\"key\":\"app:a:b\"")
        assertThrownBy<SnapshotFormatException> { ChangeRequestCodec.decode(malformed.encodeToByteArray()) }
        // A head it was checked with of such an app no longer moves: that entry alone is dropped.
        val checked = stored.replace("\"checkedWith\":{", "\"checkedWith\":{\"app: \":null,")
        checked.assert().isNotEqualTo(stored)
        ChangeRequestCodec.decode(checked.encodeToByteArray()).checkedWith.keys
            .assert().containsExactlyInAnyOrderElementsOf(
                ChangeRequestCodec.decode(stored.encodeToByteArray()).checkedWith.keys
            )
    }
}
