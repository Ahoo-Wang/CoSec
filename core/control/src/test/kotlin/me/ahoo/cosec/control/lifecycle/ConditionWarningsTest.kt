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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** DOC-9 in the direction that only narrows: a warning at the path of the literal, wherever the condition stands. */
class ConditionWarningsTest {
    private val umlaut = """{"eq":["subject.attributes.dept","Ärzte"],"ignoreCase":true}"""

    private fun warnings(body: String): List<Finding> {
        val document = PolicyDocumentCodec.decode(
            DocumentKind.PLATFORM,
            "",
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",$body}""".encodeToByteArray(),
        )
        return ArrayList<Finding>().also { ConditionWarnings.check(document, it) }
    }

    @Test
    fun `a policy condition guarding only ALLOWs, a negated rate limit condition and a negated DENY narrow`() {
        val found = warnings(
            """"policies":[{"id":"p","condition":$umlaut,"statements":[{"effect":"allow","action":"*"}]},""" +
                """{"id":"q","statements":[{"effect":"allow","action":"*"},""" +
                """{"effect":"deny","action":"order:delete","condition":{"not":$umlaut}}]}],""" +
                """"rateLimits":[{"id":"r","action":"*","condition":{"not":$umlaut},"permitsPerSecond":1}]""",
        )
        found.map { it.path }.assert().containsExactly(
            "$.policies[0].condition.eq[1]",
            "$.policies[1].statements[1].condition.not.eq[1]",
            "$.rateLimits[0].condition.not.eq[1]",
        )
        found.all { it.severity == Severity.WARNING && it.check == "COMPILE" }.assert().isTrue()
    }

    @Test
    fun `what refuses is the compiler's, and what folds within ASCII is nothing`() {
        warnings(""""policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":$umlaut}]}]""")
            .assert().isEmpty()
        val ascii = """{"eq":["subject.attributes.dept","Finance"],"ignoreCase":true}"""
        warnings(""""policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":$ascii}]}]""")
            .assert().isEmpty()
    }
}
