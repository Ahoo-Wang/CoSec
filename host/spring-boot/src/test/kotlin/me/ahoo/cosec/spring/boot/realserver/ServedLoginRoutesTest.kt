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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.spring.boot.realserver.ServedLoginRouteHost.Companion.LOGOUT
import me.ahoo.cosec.spring.boot.realserver.ServedLoginRouteHost.Companion.ORIGIN
import me.ahoo.cosec.spring.boot.realserver.ServedLoginRouteHost.Companion.REFRESH
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse

/**
 * `IDN-23` and `IDN-24` on a real Tomcat (Spring MVC) and a real Reactor Netty (WebFlux), through the starter's own
 * wiring: what the served refresh and logout routes answer, that they pass CoSec like every route (`STA-4`: with no
 * statement the answer is the pipeline's, and the operator is told), that a preflight for them is the CORS filter's
 * (`STA-5`), and that no token and nothing of a request reaches a body or a CoSec log line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class ServedLoginRoutesTest {
    private val hosts = mutableMapOf<ServedStack, ServedLoginRouteHost>()
    private val forgetting = mutableMapOf<ServedStack, ServedLoginRouteHost>()
    private val json = JsonMapper()

    private fun host(stack: ServedStack) = hosts.getOrPut(stack) {
        ServedLoginRouteHost(stack, ServedLoginRouteHost.PERMITTING)
    }

    @AfterAll
    fun stop() = (hosts.values + forgetting.values).forEach { it.close() }

    private fun body(pair: CompositeToken) =
        """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}"}"""

    private fun ServedLoginRouteHost.refresh(pair: CompositeToken) = send("POST", REFRESH, body(pair))

    private fun HttpResponse<String>.pair(): CompositeToken {
        statusCode().assert().isEqualTo(200)
        headers().firstValue("Content-Type").get().assert().startsWith("application/json")
        val node = json.readTree(body())
        node.propertyNames().toSet().assert().isEqualTo(setOf("accessToken", "refreshToken"))
        return CompositeToken(node.get("accessToken").asString(), node.get("refreshToken").asString())
    }

    private fun HttpResponse<String>.problem(): String {
        headers().firstValue("Content-Type").get().assert().startsWith("application/problem+json")
        val node = json.readTree(body())
        node.propertyNames().toSet().assert().isEqualTo(setOf("type", "title", "status", "code", "requestId"))
        node.get("requestId").asString().assert().isEqualTo(headers().firstValue("CoSec-Request-Id").get())
        return node.get("type").asString().removePrefix("https://cosec.ahoo.me/problems/")
    }

    private fun HttpResponse<String>.noStore(): HttpResponse<String> = apply {
        headers().firstValue("Cache-Control").orElse(null).assert().isEqualTo("no-store")
    }

    /** Nothing secret in a CoSec log line: no token of the case, no marker of its request. */
    private fun CapturedOutput.cosecLinesHold(vararg secrets: String) {
        val lines = all.lines().filter { COSEC_LOGGER.containsMatchIn(it) }
        for (secret in secrets) {
            lines.filter { secret in it }.assert().describedAs("CoSec log lines holding a secret").isEmpty()
        }
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a refresh answers a new pair, not to be stored`(stack: ServedStack, output: CapturedOutput) {
        val host = host(stack)
        val pair = host.login("alice")
        val next = host.refresh(pair).noStore().pair()
        next.accessToken.assert().isNotEqualTo(pair.accessToken)
        output.cosecLinesHold(pair.accessToken, pair.refreshToken, next.accessToken, next.refreshToken)
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a body that is not the pair is invalid-request, and nothing of it comes back`(
        stack: ServedStack,
        output: CapturedOutput,
    ) {
        val host = host(stack)
        val pair = host.login("bob")
        val bodies = listOf(
            """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}","x":"$MARKER"}""",
            "<script>$MARKER</script>",
            """{"accessToken":"$MARKER","refreshToken":"$MARKER"""" + " ".repeat(40 * 1024) + "}",
        )
        for (body in bodies) {
            val answer = host.send("POST", REFRESH, body).noStore()
            answer.statusCode().assert().isEqualTo(400)
            answer.problem().assert().isEqualTo("invalid-request")
            answer.body().assert().doesNotContain(MARKER).doesNotContain(pair.accessToken)
        }
        val form = host.send("POST", REFRESH, body(pair), mapOf("Content-Type" to "text/plain")).noStore()
        form.statusCode().assert().isEqualTo(400)
        output.cosecLinesHold(MARKER, pair.accessToken, pair.refreshToken)
    }

    /** IDN-16 through the served route: the pair just replaced is answered within the leeway; an older one ends it all. */
    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a pair presented again is answered as IDN-16 says`(stack: ServedStack) {
        val host = host(stack)
        val first = host.login("carol")
        val second = host.refresh(first).pair()
        host.refresh(first).pair()
        host.refresh(second).pair()
        val revoked = host.refresh(first).noStore()
        revoked.statusCode().assert().isEqualTo(401)
        revoked.problem().assert().isEqualTo("token-revoked")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `revocations that cannot be read are unavailable, on the refresh's own read, and spend nothing`(
        stack: ServedStack,
    ) {
        val host = host(stack)
        val pair = host.login("dave")
        host.revocations.failedReads.clear()
        host.revocationOutage.on = true
        val answer = try {
            host.refresh(pair).noStore()
        } finally {
            host.revocationOutage.on = false
        }
        answer.statusCode().assert().isEqualTo(503)
        answer.problem().assert().isEqualTo("unavailable")
        // The read that failed is the refresh's, of the pair's access token: the request carried no token of its own.
        host.revocations.failedReads.toList().assert().containsExactly(
            JsonMapper().readTree(
                java.util.Base64.getUrlDecoder().decode(pair.accessToken.split('.')[1])
            ).get("jti").asString(),
        )
        host.refresh(pair).pair()
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a session store that cannot answer is unavailable, a bug is an error, and neither ends the session`(
        stack: ServedStack,
    ) {
        val host = host(stack)
        val pair = host.login("erin")
        host.familyOutage.on = true
        val down = try {
            host.refresh(pair).noStore()
        } finally {
            host.familyOutage.on = false
        }
        down.statusCode().assert().isEqualTo(503)
        down.problem().assert().isEqualTo("unavailable")
        host.membershipBug.on = true
        val bug = try {
            host.refresh(pair).noStore()
        } finally {
            host.membershipBug.on = false
        }
        bug.statusCode().assert().isEqualTo(500)
        bug.problem().assert().isEqualTo("internal-error")
        host.refresh(pair).pair()
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a logout ends the session, and a logout of an ended one is done too`(
        stack: ServedStack,
        output: CapturedOutput
    ) {
        val host = host(stack)
        val pair = host.login("frank")
        val done = host.send("POST", LOGOUT, body(pair)).noStore()
        done.statusCode().assert().isEqualTo(204)
        done.body().assert().isEmpty()
        host.send("POST", LOGOUT, body(pair)).noStore().statusCode().assert().isEqualTo(204)
        val refused = host.refresh(pair)
        refused.statusCode().assert().isEqualTo(401)
        refused.problem().assert().isEqualTo("token-revoked")
        output.cosecLinesHold(pair.accessToken, pair.refreshToken)
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `only POST is served, and a preflight is the CORS filter's`(stack: ServedStack) {
        val host = host(stack)
        // A GET is decided as every route: the document permits only POST, so CoSec refuses it.
        val get = host.send("GET", REFRESH, headers = emptyMap())
        get.statusCode().assert().isEqualTo(401)
        get.problem().assert().isEqualTo("unauthorized")
        val preflight = host.send(
            "OPTIONS",
            REFRESH,
            headers = mapOf("Origin" to ORIGIN, "Access-Control-Request-Method" to "POST"),
        )
        preflight.statusCode().assert().isEqualTo(200)
        preflight.headers().firstValue("Access-Control-Allow-Origin").get().assert().isEqualTo(ORIGIN)
        preflight.headers().firstValue("CoSec-Request-Id").isPresent.assert().isFalse()
    }

    /** STA-4: the route is under the filter. Without a statement the answer is the pipeline's, and the operator is told. */
    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `with no statement for the route, CoSec refuses it and the operator is told`(
        stack: ServedStack,
        output: CapturedOutput,
    ) {
        val host = forgetting.getOrPut(stack) { ServedLoginRouteHost(stack, ServedLoginRouteHost.FORGETTING) }
        val pair = host.login("gina")
        val refused = host.refresh(pair)
        refused.statusCode().assert().isEqualTo(401)
        refused.problem().assert().isEqualTo("unauthorized")
        refused.headers().firstValue("Cache-Control").orElse(null).assert().isNotEqualTo("no-store")
        val deadline = System.nanoTime() + 10_000_000_000
        while ("cosec.authentication.login.refresh.path=$REFRESH: no statement permits an anonymous POST" !in output.all) {
            check(System.nanoTime() < deadline) { "the operator was never told" }
            Thread.sleep(50)
        }
        output.cosecLinesHold(pair.accessToken, pair.refreshToken)
    }

    private companion object {
        const val MARKER = "marker-7f3c"
        val COSEC_LOGGER = Regex("""\bm\.a\.c\.|me\.ahoo\.cosec""")
    }
}
