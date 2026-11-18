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
import java.time.Instant

/**
 * What the report tells an operator (drill entries 24, 28 and 32): an ALLOW of every action says why a mapped route stops
 * being allowed, a rejection says what to do next and cites no internal document, and data an older version wrote is
 * not called "7.x" data that "cannot be decoded".
 */
class ReportMessagesTest {
    private val at = Instant.parse("2026-10-26T00:00:00Z")

    private fun convert(vararg statements: String): Conversion = Converter().convert(
        source(listOf("p" to policy("p", "global", statements.toList())), globalIndex = setOf("p")),
        ConvertOptions(Partition.PLATFORM, at),
    )

    private val everything = """{"all":{}}"""

    @Test
    fun `an allow of every action records that a route map takes a route out of it`() {
        val report = convert(statement(everything)).report

        val entry = report.semanticChanges.single { it.change == "C8" }
        entry.location.assert().isEqualTo("policy:p#$.statements[0].action")
        entry.shape.assert().contains(
            "http:*"
        ).contains("route map").contains("business action").contains("policy of its own")
    }

    @Test
    fun `an allow of one method, a path or a deny of every action does not record it`() {
        val report = convert(
            statement("""{"all":{"method":"GET"}}"""),
            statement("\"/orders/*\""),
            statement(everything, effect = "deny"),
        ).report

        report.semanticChanges.filter { it.change == "C8" }.assert().isEmpty()
        report.semanticChanges.any { it.change == "C7" }.assert().isTrue()
    }

    @Test
    fun `a path template is rejected with what to do instead, not with an internal id`() {
        val rejection = convert(statement("\"/user/#{principal.id}/*\"")).report.rejections.single()

        rejection.kind.assert().isEqualTo(RejectionKind.ACTION_NOT_TRANSLATED)
        rejection.message.assert().contains("route map").contains("Path templates in actions")
        rejection.message.assert().doesNotContain("PR-6").doesNotContain("RFC")
        rejection.advice.assert().doesNotContain("PR-6").doesNotContain("RFC")
    }

    @Test
    fun `no rejection kind advises with an internal document`() {
        RejectionKind.entries.forEach { kind ->
            val advice = kind.advice
            advice.assert().describedAs(kind.name).doesNotContain("RFC").doesNotContain("PR-")
        }
    }

    @Test
    fun `a condition with no translation says what to write`() {
        val rejection = convert(statement("\"*\"", condition = """{"contains":{"part":"request.path","value":"x"}}"""))
            .report.rejections.single()

        rejection.kind.assert().isEqualTo(RejectionKind.CONDITION_NOT_TRANSLATED)
        rejection.message.assert().doesNotContain("RFC").contains("rewrite")
        rejection.advice.assert().doesNotContain("translat").doesNotContain("RFC")
    }

    /** `rateLimiter` is a 4.x condition: not 7.x data at all, and the message says where to look. */
    @Test
    fun `data of an older version is not in the 7_x format, and the message points to its section`() {
        val rejection = convert(statement("\"*\"", condition = """{"rateLimiter":{"permitsPerSecond":10}}"""))
            .report.rejections.first { it.kind == RejectionKind.UNDECODABLE }

        rejection.message.assert().contains("is not in the 7.x format").doesNotContain("cannot be decoded")
            .contains("From 4.x, 5.x and 6.x").contains("upgrading#from-4-x-5-x-and-6-x")
    }
}
