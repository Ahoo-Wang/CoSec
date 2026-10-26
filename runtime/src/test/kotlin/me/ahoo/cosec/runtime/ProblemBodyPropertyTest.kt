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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.random.Random

/**
 * P16 (RFC 0006 §6.2, §10.2): a problem body holds only the allowed members and values.
 *
 * Random outcomes of every kind [StatusMapping] makes, with a marker string in everything that must stay out of the
 * body (policy ids, statement names, binding ids, causes, exception messages and their cause chains), random request
 * ids (quotes, backslashes, control characters, non-ASCII, lone surrogates) and a trace id or none, are encoded and
 * parsed with a general JSON parser. The members are exactly `type`, `title`, `status`, `code`, `requestId` and
 * optionally `traceId`; `(type, title, status, code)` is a row of the vocabulary and its status is the outcome's;
 * `requestId` is the response header's; and no marker appears in the bytes.
 */
class ProblemBodyPropertyTest {
    private val json = JsonMapper.builder().build()
    private val seed = System.getProperty("cosec.problem.seed")?.toLong() ?: SEED

    @Test
    fun problemBodiesHoldOnlyTheAllowedMembers() {
        val random = Random(seed)
        val types = HashSet<ProblemType>()
        repeat(ITERATIONS) { iteration ->
            val marker = "MARK${random.nextInt(100_000, 1_000_000)}x"
            val requestId = randomString(random)
            val traceId = if (random.nextBoolean()) randomString(random) else null
            val outcome = randomOutcome(random, marker, requestId, traceId)
            val problem = outcome.problem!!
            types += problem.type
            val bytes = ProblemEncoder.encode(problem)
            val context = "seed $seed, iteration $iteration, request id ${requestId.escaped()}"

            bytes.all { it in 0..0x7F }.assert().describedAs("ASCII only; $context").isTrue()
            String(bytes, Charsets.US_ASCII).assert().describedAs(context).doesNotContain(marker)

            val body = json.readTree(bytes)
            val members = body.propertyNames().toList()
            val expected = listOf("type", "title", "status", "code", "requestId") + listOfNotNull(traceId?.let { "traceId" })
            members.assert().describedAs(context).isEqualTo(expected)

            val type = problem.type
            body.get("type").asString().assert().isEqualTo(type.uri)
            body.get("title").asString().assert().isEqualTo(type.title)
            body.get("status").asInt().assert().isEqualTo(outcome.status)
            body.get("code").asString().assert().isEqualTo(type.code)
            body.get("requestId").asString().assert().describedAs(context).isEqualTo(requestId)
            body.get("requestId").asString().assert().isEqualTo(outcome.headers.getValue("CoSec-Request-Id"))
            traceId?.let { body.get("traceId").asString().assert().describedAs(context).isEqualTo(it) }
        }
        types.assert().describedAs("every mapped row is generated").containsAll(MAPPED_TYPES)
    }

    /** One of every kind of non-proceeding outcome, carrying [marker] wherever a detail could hide. */
    private fun randomOutcome(random: Random, marker: String, requestId: String, traceId: String?): Outcome {
        val error = IllegalStateException("$marker message", RuntimeException("$marker cause"))
        val authn = randomAuthn(random, marker)
        val deny = randomDeny(random, marker)
        return when (random.nextInt(6)) {
            0 -> StatusMapping.invalidRequest(requestId, "$marker reason", traceId)
            1 -> StatusMapping.decided(requestId, deny, authn, traceId)
            2 -> StatusMapping.decided(
                requestId,
                Decision.Indeterminate(randomCause(random, marker, error)),
                authn,
                traceId
            )
            3 -> {
                val reason = if (random.nextBoolean()) {
                    Unavailability.AuthenticatorUnavailable(error)
                } else {
                    Unavailability.DeadlineExceeded
                }
                StatusMapping.unavailable(requestId, reason, traceId)
            }

            4 -> StatusMapping.error(requestId, error, traceId)
            else -> StatusMapping.decided(requestId, deny, AuthnResult.Unavailable(error), traceId)
        }
    }

    private fun randomStatement(random: Random, marker: String): StatementRef.Policy {
        val via = when (random.nextInt(3)) {
            0 -> BindingRef.Bound(BindingId("$marker-binding"), DocumentKind.entries.random(random))
            1 -> BindingRef.Attached(PolicyId("$marker-attached"))
            else -> BindingRef.Builtin
        }
        return StatementRef.Policy(PolicyId("$marker-policy"), random.nextInt(0, 10), "$marker-statement", via)
    }

    private fun randomCause(random: Random, marker: String, error: Throwable): Cause = when (random.nextInt(5)) {
        0 -> Cause.MatcherFailed(randomStatement(random, marker), random.nextBoolean(), error)
        1 -> Cause.MissingPolicies(setOf(PolicyId("$marker-missing")))
        2 -> Cause.SourceUnavailable(DocumentKind.entries.random(random), error)
        3 -> Cause.NotLoaded(DocumentKind.entries.random(random))
        else -> Cause.MembershipUnavailable(error)
    }

    private fun randomAuthn(random: Random, marker: String): AuthnResult = when (random.nextInt(3)) {
        0 -> AuthnResult.Anonymous
        1 -> AuthnResult.Rejected(AuthnResult.RejectReason.entries.random(random))
        else -> AuthnResult.Authenticated(
            AuthenticatedPrincipal(PrincipalId("$marker-principal"), TenantId("$marker-tenant"), null),
            "$marker-method",
        )
    }

    private fun randomDeny(random: Random, marker: String): Decision = when (random.nextInt(3)) {
        0 -> Decision.Deny(randomStatement(random, marker))
        1 -> Decision.Deny(null)
        else -> Decision.Deny(StatementRef.ScopeRejected(ScopeResolution.Rejected.Reason.entries.random(random)))
    }

    private fun randomString(random: Random): String {
        val length = random.nextInt(1, 24)
        return buildString {
            repeat(length) {
                append(
                    when (random.nextInt(8)) {
                        0 -> '"'
                        1 -> '\\'
                        2 -> random.nextInt(0, 0x20).toChar()
                        3 -> '\u007f'
                        4 -> random.nextInt(0x80, 0xD800).toChar()
                        5 -> random.nextInt(0xD800, 0xE000).toChar()
                        else -> random.nextInt(0x21, 0x7F).toChar()
                    }
                )
            }
        }
    }

    private fun String.escaped(): String = map { if (it.code in 0x21..0x7E) "$it" else "\\u%04x".format(it.code) }
        .joinToString("")

    private companion object {
        const val SEED = 20261026L
        const val ITERATIONS = 5_000

        /** 429 comes with the rate-limit obligations (RFC 0006 PR-5); [ProblemEncoderTest] covers its encoding. */
        val MAPPED_TYPES = ProblemType.entries - ProblemType.TOO_MANY_REQUESTS
    }
}
