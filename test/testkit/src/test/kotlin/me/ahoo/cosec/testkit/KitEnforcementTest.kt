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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URLEncoder
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * `PIP-15`, `PIP-18` (round 2 M2): the kit reads a scenario's action and resource as enforcement reads a request's, so
 * that what it confirms is what the pipeline decides. [PipelineFixture] is the pipeline itself, over the same documents.
 */
class KitEnforcementTest {
    /** Members may do anything; a HEAD under /reports is denied as written; nothing under /admin is allowed. */
    private val platform = """{"policies":[
        {"id":"members","statements":[{"effect":"allow","action":"http:*","condition":{"eq":["subject.authenticated",true]}}]},
        {"id":"no-head","statements":[{"effect":"deny","action":"http:HEAD","resource":"http:path:/reports/**"}]},
        {"id":"no-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}],
        "bindings":[
        {"id":"members","subject":"*","app":"*","space":"*","policy":"members"},
        {"id":"no-head","subject":"*","app":"*","space":"*","policy":"no-head"},
        {"id":"no-admin","subject":"*","app":"*","space":"*","policy":"no-admin"}]}"""

    private val kit = PolicyTestKit.of(platform)
    private val alice = PipelineFixture.Principal("alice", tenant = "acme")

    private fun scenario(action: String, path: String) =
        """{"credential":{"tenant":"acme","id":"alice"},"request":{"action":"$action","resource":{"type":"http","id":"$path"}}}"""

    /** The kit's outcome and the pipeline's status for one request, written both ways. */
    private fun both(method: String, path: String): Pair<EngineOutcome, Int> {
        val outcome = kit.evaluate(scenario("http:$method", path))
        val status = PipelineFixture.of(platform = platform).use { fixture ->
            runBlocking { fixture.evaluate(fixture.request(method, path, alice)).status }
        }
        return outcome to status
    }

    @Test
    fun `a HEAD is decided as the GET it is, by the kit as by the pipeline`() {
        val (outcome, status) = both("HEAD", "/reports/1")
        status.assert().isEqualTo(200)
        (outcome as EngineOutcome.Decided).decision.assert().isInstanceOf(
            me.ahoo.cosec.model.decision.Decision.Permit::class.java
        )
        // GET is GET: the DENY written on HEAD applies to neither.
        both("GET", "/reports/1").second.assert().isEqualTo(200)
    }

    @Test
    fun `a lower-case method is refused, by the kit as by the pipeline`() {
        val (outcome, status) = both("get", "/x")
        status.assert().isEqualTo(400)
        outcome.describe().assert().isEqualTo("""{"decision":"InvalidRequest","what":"method"}""")
    }

    @Test
    fun `a path the pipeline refuses is refused by the kit too`() {
        val (outcome, status) = both("GET", "/public/../admin/x")
        status.assert().isEqualTo(400)
        outcome.describe().assert().isEqualTo("""{"decision":"InvalidRequest","what":"path"}""")
    }

    /** The negative side: what the pipeline decides, the kit decides alike. */
    @Test
    fun `a request the pipeline decides is decided alike by the kit`() {
        val (denied, deniedStatus) = both("DELETE", "/admin/x")
        deniedStatus.assert().isEqualTo(403)
        (denied as EngineOutcome.Decided).decision.assert().isInstanceOf(
            me.ahoo.cosec.model.decision.Decision.Deny::class.java
        )
        val (permitted, permittedStatus) = both("GET", "/orders/1")
        permittedStatus.assert().isEqualTo(200)
        (permitted as EngineOutcome.Decided).decision.assert().isInstanceOf(
            me.ahoo.cosec.model.decision.Decision.Permit::class.java
        )
    }

    @Test
    fun `a scenario expecting an invalid request passes`() {
        kit.check(
            "lower-case",
            """{"credential":{"tenant":"acme"},"request":{"action":"http:delete","resource":{"type":"http","id":"/x"}},
               "expect":{"decision":"InvalidRequest"}}""",
        )
    }

    /** A document id too long for a file name, as `cosec-migrate convert` names its file (`ConvertedDirectory`). */
    private fun boundedName(id: String): String {
        val encoded = URLEncoder.encode(id, Charsets.UTF_8)
        val hash = MessageDigest.getInstance(
            "SHA-256"
        ).digest(id.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        var end = 200 - ".json".length - "~".length - hash.length
        if (encoded[end - 1] == '%') end -= 1 else if (encoded[end - 2] == '%') end -= 2
        return encoded.substring(0, end) + "~" + hash + ".json"
    }

    private fun converted(directory: Path, tenant: String, report: Boolean): Path {
        val name = boundedName(tenant)
        directory.resolve("platform.json").writeText(
            """{"policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*"}]}],
               "bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"}]}""",
        )
        directory.resolve("tenants").createDirectories().resolve(name).writeText(
            """{"policies":[{"id":"no-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}],
               "bindings":[{"id":"no-admin","subject":"*","app":"*","space":"*","policy":"no-admin"}]}""",
        )
        if (report) {
            directory.resolve(
                "report.json"
            ).writeText("""{"accepted":true,"documentIds":{"tenants/$name":"$tenant"}}""")
        }
        return directory
    }

    /** Round 2 L1: the id of a shortened file name is the one `report.json` records, never the decoded name. */
    @Test
    fun `a shortened file name holds the id the report records`(@TempDir directory: Path) {
        val tenant = "租".repeat(30)
        val kit = PolicyTestKit.load(converted(directory, tenant, report = true))
        kit.check(
            "the tenant's DENY",
            """{"credential":{"tenant":"$tenant","id":"alice"},
               "request":{"action":"http:GET","resource":{"type":"http","id":"/admin/x"}},"expect":{"decision":"Deny"}}""",
        )
    }

    @Test
    fun `a shortened file name without the report fails rather than naming another id`(@TempDir directory: Path) {
        val unreported = converted(directory, "租".repeat(30), report = false)
        val error = assertThrows<ScenarioFormatException> { PolicyTestKit.load(unreported) }
        error.message.assert().contains("report.json")
    }
}
