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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `MIG-5` (R2E-1): a converted DENY or rate limit rule written in the vocabulary of an unmapped request stops applying to
 * a route a route map later maps, which nothing at run time says, so the report records each as C9, as it records the
 * ALLOW of every action as C8.
 */
class UnmappedVocabularyTest {
    @TempDir
    lateinit var temp: Path

    private fun convert(vararg statements: String, condition: String? = null): ConversionReport = Converter().convert(
        source(listOf("p" to policy("p", "global", statements.toList(), condition)), globalIndex = setOf("p")),
    ).report

    private fun ConversionReport.c9() = semanticChanges.filter { it.change == "C9" }

    @Test
    fun `a DENY on a path records C9 for its http action and its http path resource`() {
        val entry = convert(statement("""{"path":{"pattern":"/admin/**"}}""", effect = "deny")).c9().single()

        entry.location.assert().isEqualTo("policy:p#$.statements[0]")
        entry.shape.assert().contains("DENY written for a request no route map maps")
            .contains("an http: action").contains("an http:path: resource")
            .contains("Rewrite it for the mapped action before adding the route map")
    }

    @Test
    fun `a DENY of every action on request path or request method records C9, the path one with the encoding`() {
        val report = convert(
            statement(condition = """{"startsWith":{"part":"request.path","value":"/admin"}}""", effect = "deny"),
            statement(condition = """{"eq":{"part":"request.method","value":"DELETE"}}""", effect = "deny"),
        )

        val (path, method) = report.c9()
        path.shape.assert().contains("a condition on resource.id").contains("percent-encoded").contains("/%61dmin/x")
        method.shape.assert().contains("a condition on action.verb").doesNotContain("percent-encoded")
    }

    @Test
    fun `a policy condition on request path over a DENY records C9 at the policy condition`() {
        val entry = convert(
            statement(effect = "deny"),
            condition = """{"startsWith":{"part":"request.path","value":"/admin"}}""",
        ).c9().single()

        entry.location.assert().isEqualTo("policy:p#$.condition")
        entry.shape.assert().contains("a condition on resource.id")
    }

    @Test
    fun `a rate limit rule on a path records C9 as a rule that is no longer limited`() {
        val rules = Files.writeString(
            temp.resolve("rate-limit.json"),
            """[{"id":"admin","action":{"path":{"pattern":"/admin/**"}},"permitsPerSecond":1}]""",
        )
        val report = Converter().convert(source().withRateLimitRules(ExportDirectory.rateLimitRules(rules))).report

        val entry = report.c9().single()
        entry.location.assert().isEqualTo("rate-limit.json[0]")
        entry.shape.assert().contains("rate limit rule written for a request no route map maps")
            .contains("the route is no longer limited")
    }

    @Test
    fun `an ALLOW, a DENY of every action without a condition or on something else records no C9`() {
        val report = convert(
            statement("""{"path":{"pattern":"/admin/**"}}"""),
            statement(condition = """{"startsWith":{"part":"request.path","value":"/admin"}}"""),
            statement(effect = "deny"),
            statement(condition = """{"eq":{"part":"request.remoteIp","value":"10.0.0.1"}}""", effect = "deny"),
        )

        report.c9().assert().isEmpty()
        // The ALLOW side has its own entry (C8) and the DENY of every action its own (C7).
        report.semanticChanges.any { it.change == "C7" }.assert().isTrue()
    }
}
