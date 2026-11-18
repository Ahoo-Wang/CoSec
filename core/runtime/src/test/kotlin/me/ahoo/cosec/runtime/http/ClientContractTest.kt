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

import io.mockk.mockk
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.contract.ContractFiles
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Modifier
import java.time.Duration

/**
 * `PIP-25`: what the server answers a client, as `contract/` states it. Every problem type's status, headers and body
 * members (exactly those, no more, none missing), and the `CoSec-*` request headers, the request id's grammar and the
 * response headers of each outcome. The TypeScript client is tested against the same files.
 *
 * What it does not cover: a `CoSec-*` request header is found in the file only when `CoSecParameters` declares it as a
 * string constant, so a parameter read from a literal elsewhere is not; `CoSec-Identity` is not in the file, since a
 * client neither sends nor reads it (a gateway's services behind it do). The response headers are those of the outcomes
 * the test builds, one per path a pipeline reaches (a permit with and without a rate limit, each problem type), not
 * those a host adds on its own.
 */
@OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)
class ClientContractTest {
    private val files = ContractFiles()

    @Test
    fun `every problem type answers its contract file`() {
        for (type in ProblemType.entries) {
            val outcome = outcomeOf(type)
            outcome.problem?.type.assert().describedAs("the path of ${type.slug} answers it").isEqualTo(type)
            files.check("problems/${type.slug}.json", problemFile(outcome))
        }
        files.only("problems", ProblemType.entries.map { "${it.slug}.json" }.toSet())
    }

    @Test
    fun `a trace id is the one optional member of a problem body`() {
        for (type in ProblemType.entries) {
            val plain = files.parse(ProblemEncoder.encode(Problem(type, REQUEST_ID, null))) as ObjectNode
            val traced = files.parse(ProblemEncoder.encode(Problem(type, REQUEST_ID, TRACE_ID))) as ObjectNode
            traced.get(TRACE_ID_MEMBER).asString().assert().isEqualTo(TRACE_ID)
            traced.deepCopy().also { it.remove(TRACE_ID_MEMBER) }.assert().describedAs(type.slug).isEqualTo(plain)
        }
    }

    @Test
    fun `the request id's pattern accepts exactly what the server does`() {
        RequestIds.MAX_LENGTH.assert().isEqualTo(REQUEST_ID_MAX)
        val pattern = Regex(REQUEST_ID_PATTERN)
        // The whole of Latin-1 and Latin Extended-A, which holds every control character and every look-alike: a
        // character the pattern lets through and the server does not, or the other way round, fails here.
        val single = (0..LAST_SAMPLED_CHARACTER).map { it.toChar().toString() }
        val lengths = listOf(0, 1, REQUEST_ID_MAX, REQUEST_ID_MAX + 1).map { "x".repeat(it) }
        val others = listOf("A-z_0.9", "a b", "a\"b", "a\n", "\u00e9")
        for (sample in single + lengths + others) {
            RequestIds.isAcceptable(sample).assert()
                .describedAs("first character %s, length %d", sample.firstOrNull()?.code, sample.length)
                .isEqualTo(pattern.matches(sample))
        }
        // The loops above are not vacuous: both sides accept, and both refuse.
        single.count { pattern.matches(it) }.assert().isEqualTo(ACCEPTED_SINGLES)
        lengths.map { pattern.matches(it) }.assert().isEqualTo(listOf(false, true, true, false))
    }

    @Test
    fun `the headers a client sends and reads are the contract's`() {
        val file = files.objectNode()
        file.set("request", requestHeaders())
        file.putObject("requestId").put("pattern", REQUEST_ID_PATTERN)
        val response = file.putObject("response")
        val permit = names(permit(null))
        val counted = names(permit(RateLimitResult(true, LIMIT, LIMIT - 1, Duration.ofSeconds(RESET_SECONDS))))
        val everyAnswer = ProblemType.entries.map { names(outcomeOf(it)) }.fold(permit) { common, next ->
            common.intersect(next)
        }
        response.set("everyAnswer", array(everyAnswer))
        response.set("problem", array(names(outcomeOf(ProblemType.FORBIDDEN))))
        response.set("tooManyRequests", array(names(outcomeOf(ProblemType.TOO_MANY_REQUESTS))))
        response.set("permitCounted", array(counted - permit))
        files.check("headers.json", file)
    }

    /** Every `CoSec-*` string constant `CoSecParameters` declares: the headers a client may send. */
    private fun requestHeaders(): ArrayNode {
        val declared = CoSecParameters::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.apply { isAccessible = true }.get(null) as String }
            .filter { it.startsWith("CoSec-") }
        declared.assert().containsAll(listOf(CoSecParameters.SPACE_ID, CoSecParameters.DEVICE_ID))
        return array(declared.toSet())
    }

    private fun array(names: Set<String>): ArrayNode = files.arrayNode().apply { names.sorted().forEach { add(it) } }

    private fun names(outcome: Outcome): Set<String> = outcome.headers.keys

    private fun problemFile(outcome: Outcome): ObjectNode {
        val problem = checkNotNull(outcome.problem)
        val file = files.objectNode()
        file.put("status", outcome.status)
        file.set("headers", headersOf(outcome))
        file.set("body", files.parse(ProblemEncoder.encode(problem)))
        file.putObject("optionalMembers").put(TRACE_ID_MEMBER, "string")
        return file
    }

    /** The response headers of [outcome], by name, in a stable order. */
    private fun headersOf(outcome: Outcome): ObjectNode = files.objectNode().apply {
        outcome.headers.toSortedMap().forEach { (name, value) -> put(name, value) }
    }

    /** What a request that was permitted answers, spending [rateLimit] when it is not null. */
    private fun permit(rateLimit: RateLimitResult?): Outcome = StatusMapping.decided(
        REQUEST_ID,
        Decision.Permit(statement()),
        AuthnResult.Anonymous,
        null,
        mockk<RequestSecurity>(),
        rateLimit,
    )

    private fun statement() =
        StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("b"), DocumentKind.TENANT))

    /**
     * The outcome a client receives for [type], by the function of [StatusMapping] the path of a request reaches for
     * it: the pipeline's refusals, a deny by whom it was authenticated as, a scope rejection, an unavailable decision,
     * an unmodelled error and a rate limit. `identityRejected`, which a forwarded identity's refusals use, answers
     * `invalid-request`, `token-expired` and `token-invalid` through the same private `problem`, so it adds no body or
     * header that these paths do not write.
     */
    private fun outcomeOf(type: ProblemType): Outcome {
        val deny = Decision.Deny(statement())
        val authenticated = AuthnResult.Authenticated(
            AuthenticatedPrincipal(PrincipalId("p"), TenantId("t"), null),
            "m"
        )
        fun rejected(reason: AuthnResult.RejectReason) =
            StatusMapping.decided(REQUEST_ID, deny, AuthnResult.Rejected(reason), null)
        return when (type) {
            ProblemType.INVALID_REQUEST -> StatusMapping.invalidRequest(REQUEST_ID, "contract", null)
            ProblemType.UNAUTHORIZED -> StatusMapping.decided(REQUEST_ID, deny, AuthnResult.Anonymous, null)
            ProblemType.TOKEN_INVALID -> rejected(AuthnResult.RejectReason.TOKEN_INVALID)
            ProblemType.TOKEN_EXPIRED -> rejected(AuthnResult.RejectReason.TOKEN_EXPIRED)
            ProblemType.TOKEN_REVOKED -> rejected(AuthnResult.RejectReason.TOKEN_REVOKED)
            ProblemType.FORBIDDEN -> StatusMapping.decided(REQUEST_ID, deny, authenticated, null)
            ProblemType.SCOPE_REJECTED -> StatusMapping.decided(
                REQUEST_ID,
                Decision.Deny(StatementRef.ScopeRejected(ScopeResolution.Rejected.Reason.APP_MISMATCH)),
                authenticated,
                null,
            )
            ProblemType.TOO_MANY_REQUESTS -> StatusMapping.tooManyRequests(
                REQUEST_ID,
                Decision.Permit(statement()),
                "rule",
                RateLimitResult(false, LIMIT, 0, Duration.ofSeconds(RESET_SECONDS)),
                null,
            )
            ProblemType.UNAVAILABLE -> StatusMapping.unavailable(REQUEST_ID, Unavailability.DeadlineExceeded, null)
            ProblemType.INTERNAL_ERROR -> StatusMapping.error(REQUEST_ID, IllegalStateException("contract"), null)
        }
    }

    private companion object {
        const val REQUEST_ID = "golden-request-id"
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val TRACE_ID_MEMBER = "traceId"
        const val REQUEST_ID_MAX = 64
        const val REQUEST_ID_PATTERN = "^[A-Za-z0-9._-]{1,64}$"
        const val LAST_SAMPLED_CHARACTER = 0x17F

        /** `A-Z`, `a-z`, `0-9`, `.`, `_`, `-`. */
        const val ACCEPTED_SINGLES = 26 + 26 + 10 + 3
        const val LIMIT = 10L
        const val RESET_SECONDS = 30L
    }
}
