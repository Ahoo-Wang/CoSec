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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Acknowledgement
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `CTL-61` (R2C-11): the control API answers from a response model of its own. A change answer names the members its
 * codec writes, never the stored record's bookkeeping, so a member added to the record reaches no client; a decision's
 * closed sets come from one table each, and a refused scope says why under `reason`, never under `cause`.
 */
class ControlAnswersTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private fun call(method: String, path: String, body: String? = null): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), alice)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    @Test
    fun `a change answer holds only what its codec writes, never the record's bookkeeping - CTL-61`() {
        val proposal = """{"document":"tenant:acme","base":1,"note":"more",""" +
            """"operations":[{"op":"putPolicy","policy":{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
        val (created, proposed) = call("POST", "/changes", proposal)
        created.assert().isEqualTo(201)
        val id = proposed.get("id").asString()
        val (_, staged) = call("POST", "/changes/$id:shadow")
        val (_, read) = call("GET", "/changes/$id")
        for (answer in listOf(proposed, staged, read)) {
            for (member in BOOKKEEPING) answer.has(member).assert().describedAs(member).isFalse()
            answer.properties().map { it.key }.toSet().assert().isSubsetOf(ANSWERED)
        }
        read.get("shadow").has("window").assert().isTrue()
        // What the record keeps and the answer leaves out: the store's bookkeeping, every piece of it.
        val stored = CoSecJson.mapper.readTree(ChangeRequestCodec.encode(ChangeSamples.full))
        val answered = ChangeAnswer.tree(ChangeSamples.full)
        stored.properties().map { it.key }.toSet().minus(answered.properties().map { it.key }.toSet())
            .assert().containsExactlyInAnyOrder(*BOOKKEEPING.toTypedArray())
        stored.get("shadow").has("reporters").assert().isTrue()
        answered.get("shadow").has("reporters").assert().isFalse()
        answered.get("approval").properties().map { it.key }.assert()
            .containsExactlyInAnyOrder("approver", "at", "candidate", "covers")
    }

    @Test
    fun `an open window's answer names at most twenty instances it waits for, and counts the rest - CTL-10`() {
        val waiting = (1..25).map { "i-%02d".format(it) }
        val open = ChangeAnswer.tree(ChangeSamples.open, Acknowledgement(3, waiting)).get("shadow")
        open.get("acknowledged").asInt().assert().isEqualTo(3)
        open.get("waitingFor").get("instances").values().map { it.asString() }.assert().isEqualTo(waiting.take(20))
        open.get("waitingFor").get("more").asInt().assert().isEqualTo(5)
        // A closed window, and an answer that read no statuses, say nothing of acknowledgements.
        ChangeAnswer.tree(ChangeSamples.closed, Acknowledgement(3, waiting)).get("shadow").has("waitingFor")
            .assert().isFalse()
        ChangeAnswer.tree(ChangeSamples.open).get("shadow").has("acknowledged").assert().isFalse()
    }

    @Test
    fun `a refused scope says why under reason, never under cause - CTL-61`() {
        val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order",""" +
            """"space":"team-b","action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""
        val (status, simulation) = call("POST", "/simulate", """{"request":$request}""")
        status.assert().isEqualTo(200)
        val enforced = simulation.get("enforced")
        enforced.get("decision").asString().assert().isEqualTo("Rejected")
        enforced.get("reason").asString().assert().isEqualTo("SPACE_NOT_MEMBER")
        enforced.has("cause").assert().isFalse()
    }

    @Test
    fun `an undecided decision names the kind of its cause from one table, never a class's name - CTL-61`() {
        val at = StatementRef.Policy(PolicyId("p"), 0, "", BindingRef.Builtin)
        val failure = IllegalStateException("down")
        val causes = mapOf(
            Cause.MatcherFailed(at, false, failure) to "MatcherFailed",
            Cause.MissingPolicies(setOf(PolicyId("p"))) to "MissingPolicies",
            Cause.AmbiguousPolicy(setOf(PolicyId("p"))) to "AmbiguousPolicy",
            Cause.SourceUnavailable(DocumentKind.TENANT, failure) to "SourceUnavailable",
            Cause.DocumentInvalid(DocumentKind.TENANT, 2, failure) to "DocumentInvalid",
            Cause.NotLoaded(DocumentKind.APP) to "NotLoaded",
            Cause.BoundaryUnavailable(PolicyId("b"), failure) to "BoundaryUnavailable",
            Cause.AttributeUnavailable(setOf("ext.geo"), at) to "AttributeUnavailable",
            Cause.CheckFailed(failure) to "CheckFailed",
            Cause.MembershipUnavailable(failure) to "MembershipUnavailable",
            Cause.RequiredListenerFailed(listOf("audit")) to "RequiredListenerFailed",
            Cause.RateLimiterUnavailable("reads") to "RateLimiterUnavailable",
        )
        causes.forEach { (cause, value) -> ControlVocabulary.cause(cause).assert().isEqualTo(value) }
        causes.values.toSet().assert().isEqualTo(ControlVocabulary.CAUSES.toSet())
    }

    private companion object {
        /** What the record keeps for the store's own work, and no answer holds. */
        val BOOKKEEPING = listOf("format", "checkedWith", "checkedApps")

        /** Every top-level member a change answer may hold (`contract/control/change.json`). */
        val ANSWERED = setOf(
            "id", "key", "base", "operations", "author", "origin", "note", "kind", "state", "findings", "candidate",
            "createdAt", "updatedAt", "skipShadow", "publishedRevision", "escalations", "approval", "shadow",
            "confirmations",
        )
    }
}
