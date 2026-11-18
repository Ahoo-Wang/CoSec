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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * `PIP-25`: what the server answers a client, as `contract/` states it. Every problem type's status, headers and body
 * members (exactly those, no more, none missing), and the `CoSec-*` request headers, the request id's grammar and the
 * response headers of each outcome. The TypeScript client is tested against the same files.
 */
class ClientContractTest {
    @Test
    fun `every problem type answers its contract file`() {
        for (type in ProblemType.entries) {
            ContractFiles.check("problems/${type.slug}.json", problemFile(outcomeOf(type)))
        }
        ContractFiles.only("problems", ProblemType.entries.map { "${it.slug}.json" }.toSet())
    }

    @Test
    fun `a trace id is the one optional member of a problem body`() {
        for (type in ProblemType.entries) {
            val plain = ContractFiles.parse(ProblemEncoder.encode(Problem(type, REQUEST_ID, null))) as ObjectNode
            val traced = ContractFiles.parse(ProblemEncoder.encode(Problem(type, REQUEST_ID, TRACE_ID))) as ObjectNode
            traced.get(TRACE_ID_MEMBER).asString().assert().isEqualTo(TRACE_ID)
            traced.deepCopy().also { it.remove(TRACE_ID_MEMBER) }.assert().describedAs(type.slug).isEqualTo(plain)
        }
    }

    @Test
    fun `the headers a client sends and reads are the contract's`() {
        RequestIds.MAX_LENGTH.assert().isEqualTo(REQUEST_ID_MAX)
        val pattern = Regex(REQUEST_ID_PATTERN)
        val samples =
            listOf("a", "A-z_0.9", "x".repeat(REQUEST_ID_MAX), "", "x".repeat(REQUEST_ID_MAX + 1), "a b", "é", "a\"b")
        for (sample in samples) {
            RequestIds.isAcceptable(sample).assert().describedAs(sample).isEqualTo(pattern.matches(sample))
        }
        val file = ContractFiles.objectNode()
        file.set(
            "request",
            ContractFiles.arrayNode().apply {
                listOf(
                    CoSecParameters.SPACE_ID,
                    CoSecParameters.DEVICE_ID,
                    CoSecParameters.REQUEST_ID
                ).forEach { add(it) }
            }
        )
        file.putObject("requestId").put("pattern", REQUEST_ID_PATTERN)
        val response = file.putObject("response")
        response.set("everyAnswer", ContractFiles.arrayNode().add(CoSecParameters.REQUEST_ID))
        response.set("problem", namesOf(outcomeOf(ProblemType.FORBIDDEN)))
        response.set("tooManyRequests", namesOf(outcomeOf(ProblemType.TOO_MANY_REQUESTS)))
        response.set(
            "permitCounted",
            ContractFiles.arrayNode().apply {
                listOf(
                    StatusMapping.RATE_LIMIT_LIMIT,
                    StatusMapping.RATE_LIMIT_REMAINING,
                    StatusMapping.RATE_LIMIT_RESET
                )
                    .forEach { add(it) }
            }
        )
        ContractFiles.check("headers.json", file)
    }

    private fun problemFile(outcome: Outcome): ObjectNode {
        val problem = checkNotNull(outcome.problem)
        val file = ContractFiles.objectNode()
        file.put("status", outcome.status)
        file.set("headers", headersOf(outcome))
        file.set("body", ContractFiles.parse(ProblemEncoder.encode(problem)))
        file.putObject("optionalMembers").put(TRACE_ID_MEMBER, "string")
        return file
    }

    /** The response headers of [outcome], by name, in a stable order. */
    private fun headersOf(outcome: Outcome): ObjectNode = ContractFiles.objectNode().apply {
        outcome.headers.toSortedMap().forEach { (name, value) -> put(name, value) }
    }

    private fun namesOf(outcome: Outcome) = ContractFiles.arrayNode().apply {
        outcome.headers.keys.sorted().forEach { add(it) }
    }

    /**
     * The outcome a client receives for [type]. Every refusal but a rate limit's is written by one function of
     * [StatusMapping], whichever path reaches it; `identityRejected` reaches it for any type.
     */
    private fun outcomeOf(type: ProblemType): Outcome = when (type) {
        ProblemType.TOO_MANY_REQUESTS -> StatusMapping.tooManyRequests(
            REQUEST_ID,
            Decision.Permit(
                StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("b"), DocumentKind.TENANT))
            ),
            "rule",
            RateLimitResult(false, LIMIT, 0, Duration.ofSeconds(RESET_SECONDS)),
            null,
        )
        else -> StatusMapping.identityRejected(REQUEST_ID, type, "contract", null)
    }

    private companion object {
        const val REQUEST_ID = "golden-request-id"
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val TRACE_ID_MEMBER = "traceId"
        const val REQUEST_ID_MAX = 64
        const val REQUEST_ID_PATTERN = "^[A-Za-z0-9._-]{1,64}$"
        const val LIMIT = 10L
        const val RESET_SECONDS = 30L
    }
}
