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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.migrate.ConvertedReach.Companion.HTTP_GET
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER_DELETE
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER_PATH
import me.ahoo.cosec.migrate.ConvertedReach.Companion.security
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * MIG-2, drill entry 7: what a 7.x "every action" reaches once converted. 7.x decided HTTP requests only, so `all` was
 * every HTTP request; the control plane's actions (`cosec:*`, RFC 0007 §7.1) and those of checks in code did not exist,
 * and a converted ALLOW must not grant them. A converted DENY of every action keeps refusing all of it.
 */
class AllActionReachTest {
    private val at = Instant.parse("2026-10-26T00:00:00Z")
    private val acme = TenantId("acme")

    /** The 7.x shapes of "every action": the typed `all`, the pattern `*`, a path array holding `*`, a composite. */
    private fun everyAction(form: String): String = when (form) {
        "typed" -> """{"all":{}}"""
        "pattern" -> "\"*\""
        "path-array" -> """{"path":["/orders/**","*"]}"""
        "composite" -> """{"composite":[{"all":{}},"/orders/**"]}"""
        else -> error(form)
    }

    private fun global(vararg statements: String) =
        "everyone" to policy("everyone", "global", statements.toList())

    @ParameterizedTest
    @ValueSource(strings = ["typed", "pattern", "path-array", "composite"])
    fun `a global allow of every action converts to no control-plane action`(form: String) {
        val reach = ConvertedReach(convert(global(statement(everyAction(form), condition = AUTHENTICATED))).documents)
        val alice = security(acme, "alice")
        // What 7.x permitted stays permitted.
        reach.permits(alice, HTTP_GET, ORDER_PATH).assert().isTrue()
        reach.controlActions(alice, SnapshotKey.Tenant(acme.value)).assert()
            .describedAs("control-plane actions a 7.x global `all` reaches for a member of acme")
            .isEmpty()
        // An action of a check in code, which 7.x had none of.
        reach.permits(alice, ORDER_DELETE, ORDER).assert().describedAs("order:delete on order/1").isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["typed", "pattern", "path-array", "composite"])
    fun `an attachable allow of every action converts to no control-plane action`(form: String) {
        val documents = convert(
            "all-of-it" to policy("all-of-it", "custom", listOf(statement(everyAction(form))))
        ).documents
        val reach = ConvertedReach(documents)
        val alice = security(acme, "alice", policies = listOf("all-of-it"))
        reach.permits(alice, HTTP_GET, ORDER_PATH).assert().isTrue()
        reach.controlActions(alice, SnapshotKey.Tenant(acme.value)).assert()
            .describedAs("control-plane actions a 7.x attachable `all` reaches for a token that lists it")
            .isEmpty()
        reach.permits(alice, ORDER_DELETE, ORDER).assert().isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["typed", "pattern", "path-array", "composite"])
    fun `a global allow of every action gives the platform tenant no control of the platform`(form: String) {
        val reach = ConvertedReach(convert(global(statement(everyAction(form), condition = AUTHENTICATED))).documents)
        reach.controlActions(security(TenantId.PLATFORM, "ops"), SnapshotKey.Platform).assert()
            .describedAs("control-plane actions a 7.x global `all` reaches for a member of (platform)")
            .isEmpty()
    }

    /**
     * A DENY of every action keeps refusing everything it refused, the control plane and checks in code included, and
     * the report says so at its action (C7): a DENY is never narrower (MIG-1), and here it is wider, on purpose.
     */
    @ParameterizedTest
    @ValueSource(strings = ["typed", "pattern", "path-array", "composite"])
    fun `a DENY of every action still refuses everything, and is recorded`(form: String) {
        val conversion = convert(
            global(
                statement(everyAction(form), condition = AUTHENTICATED),
                statement(everyAction(form), "deny", AUTHENTICATED, name = "frozen"),
            ),
        )
        conversion.documents.getValue(SnapshotKey.Platform).policies.single().statements.map { it.actions }.assert()
            .containsExactly(listOf("http:*"), listOf("*"))
        conversion.report.semanticChanges.filter { it.change == "C7" }.map { it.location }.assert()
            .containsExactly("policy:everyone#$.statements[1].action")
        val reach = ConvertedReach(conversion.documents)
        val alice = security(acme, "alice")
        reach.permits(alice, HTTP_GET, ORDER_PATH).assert().isFalse()
        reach.permits(alice, ORDER_DELETE, ORDER).assert().isFalse()
        reach.controlActions(alice, SnapshotKey.Tenant(acme.value)).assert().isEmpty()
    }

    /**
     * The whole of it, through the control plane's own change flow: a member of acme whose only grant is the converted
     * 7.x "signed-in users may do anything" gives bob every order action in acme's document, skipping the shadow
     * window. Her own change is analyzed and waits for approval (RC1-10), which any other member of acme, holding the
     * same grant, gives. Before MIG-2 it published.
     */
    @Test
    fun `a converted allow of every action publishes nothing through the control plane`() = runBlocking<Unit> {
        val reach = ConvertedReach(
            convert(global(statement(everyAction("typed"), condition = AUTHENTICATED))).documents
        )
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val store = InMemoryControlStore(clock)
        val revisions = Revisions(store, clock)
        val changes = Changes(store, revisions, { ChangeId("grant-bob") }, reach.authorizer, clock)
        val alice = checkNotNull(Operator.of(security(acme, "alice")))
        val carol = checkNotNull(Operator.of(security(acme, "carol")))
        val key = SnapshotKey.Tenant(acme.value)
        val grant = PolicyDefinition(
            PolicyId("orders"),
            attachable = false,
            condition = null,
            statements = listOf(StatementSource("", Effect.ALLOW, listOf("order:*"))),
        )
        val bob = Binding(
            BindingId("bob-orders"),
            SubjectRef.Principal(acme, PrincipalId("bob")),
            BindingScope(Match.Any, Match.Any),
            grant.id,
        )
        val published = try {
            val proposed = changes.propose(
                alice,
                key,
                0,
                listOf(Operation.PutPolicy(grant), Operation.PutBinding(bob)),
                "grant bob"
            )
            val ready = changes.skipShadow(alice, proposed.id, "urgent").let { skipped ->
                if (skipped.state == ChangeState.AWAITING_APPROVAL) {
                    changes.approve(
                        carol,
                        skipped.id,
                        skipped.candidate!!
                    )
                } else {
                    skipped
                }
            }
            changes.publish(alice, ready.id)
        } catch (refused: ChangeRejectedException) {
            refused.status.assert().describedAs("${refused.message}").isEqualTo(FORBIDDEN)
            null
        }
        published.assert().describedAs(
            "what alice published in acme's document, carol approving with the same converted grant"
        ).isNull()
        revisions.head(key).assert().isNull()
    }

    private fun convert(vararg policies: Pair<String, String>): Conversion {
        val globals = policies.filter { (_, json) -> "\"type\":\"global\"" in json }.map { it.first }.toSet()
        val conversion = Converter().convert(
            source(policies.toList(), globalIndex = globals),
            ConvertOptions(Partition.PLATFORM, at)
        )
        conversion.report.accepted.assert().isTrue()
        return conversion
    }

    private companion object {
        const val AUTHENTICATED = """{"authenticated":{}}"""
        const val FORBIDDEN = 403
    }
}
