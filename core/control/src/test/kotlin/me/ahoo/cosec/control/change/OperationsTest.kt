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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** RFC 0007 §6.7 and §10.1: what entry-level operations make of a base, and the operations `:apply` derives. */
class OperationsTest {
    private fun rule(id: String, permits: Double = 10.0) = RateLimitSource(
        id,
        StatementSource(id, Effect.DENY, listOf("order:read")),
        key = null,
        limit = RateLimitSpec(permits),
    )

    private fun document(vararg rules: RateLimitSource) =
        PolicyDocument(
            DocumentKind.TENANT,
            "acme",
            1,
            Instant.EPOCH,
            emptyList(),
            emptyList(),
            rateLimits = rules.toList()
        )

    @Test
    fun `rate limits are put, removed and diffed`() {
        val base = document(rule("old"), rule("kept"))
        val desired = document(rule("kept", permits = 20.0), rule("new"))

        val merge = Operations.diff(ACME, base, desired, ApplyMode.MERGE)
        merge.map { it::class }.assert().containsExactly(Operation.PutRateLimit::class, Operation.PutRateLimit::class)
        (merge[0] as Operation.PutRateLimit).ifMatch.assert().isEqualTo(EntryDigest.of(rule("kept")))
        (merge[1] as Operation.PutRateLimit).ifMatch.assert().isNull()
        Operations.apply(ACME, base, merge).candidate!!.rateLimits.map { it.id }
            .assert().containsExactly("old", "kept", "new")

        val replace = Operations.diff(ACME, base, desired, ApplyMode.REPLACE)
        val replaced = Operations.apply(ACME, base, replace).candidate!!.rateLimits
        replaced.map { it.id }.assert().containsExactly("kept", "new")
        replaced.first().limit.permitsPerSecond.assert().isEqualTo(20.0)
        Operations.diff(ACME, desired, desired, ApplyMode.REPLACE).assert().isEmpty()
    }

    /** Obligations run in rule order and a 429 names the first exhausted rule: a reorder is a change, and diff keeps it. */
    @Test
    fun `diff round-trips the order of the rules`() {
        fun ids(document: PolicyDocument) = document.rateLimits.map { it.id }
        val base = document(rule("a"), rule("b"), rule("c"))
        listOf(
            document(rule("c"), rule("a"), rule("b")),
            document(rule("a"), rule("c"), rule("b")),
            document(rule("a"), rule("x"), rule("b"), rule("c")),
            document(rule("b")),
        ).forEach { desired ->
            val operations = Operations.diff(ACME, base, desired, ApplyMode.REPLACE)
            ids(Operations.apply(ACME, base, operations).candidate!!).assert().isEqualTo(ids(desired))
        }
        Operations.diff(ACME, base, document(rule("c"), rule("a"), rule("b")), ApplyMode.REPLACE).assert().isNotEmpty()
        Operations.diff(ACME, base, base, ApplyMode.REPLACE).assert().isEmpty()
    }

    @Test
    fun `a rate limit operation on what the author did not see is refused`() {
        val base = document(rule("old"))
        Operations.apply(ACME, base, listOf(Operation.PutRateLimit(rule("old", 5.0), ifMatch = "stale")))
            .findings.single().check.assert().isEqualTo("IF_MATCH")
        Operations.apply(ACME, base, listOf(Operation.RemoveRateLimit("absent"))).findings.single().message
            .assert().contains("rate limit [absent]")
        Operations.apply(ACME, base, listOf(Operation.RemoveRateLimit("old", EntryDigest.of(rule("old")))))
            .candidate!!.rateLimits.assert().isEmpty()
    }

    @Test
    fun `rate limit operations keep through the stored change`() {
        val node = ChangeRequestCodec.operation(
            ACME,
            me.ahoo.cosec.runtime.json.CoSecJsonSerializer.readTree(
                """{"op":"putRateLimit","rateLimit":{"id":"r","action":"order:read","permitsPerSecond":5},"ifMatch":"x"}"""
            ),
        ) as Operation.PutRateLimit
        node.rule.id.assert().isEqualTo("r")
        node.ifMatch.assert().isEqualTo("x")
        val removed = ChangeRequestCodec.operation(
            ACME,
            me.ahoo.cosec.runtime.json.CoSecJsonSerializer.readTree("""{"op":"removeRateLimit","id":"r"}"""),
        ) as Operation.RemoveRateLimit
        removed.id.assert().isEqualTo("r")

        val proposed = kotlinx.coroutines.runBlocking {
            ChangeHarness().changes.propose(
                ALICE_OP,
                ACME,
                0,
                listOf(Operation.PutRateLimit(rule("r")), Operation.RemoveRateLimit("gone", "digest")),
                "limits",
            )
        }
        val stored = ChangeRequestCodec.decode(ChangeRequestCodec.encode(proposed)).operations
        (stored[0] as Operation.PutRateLimit).rule.id.assert().isEqualTo("r")
        (stored[1] as Operation.RemoveRateLimit).ifMatch.assert().isEqualTo("digest")
    }
}
