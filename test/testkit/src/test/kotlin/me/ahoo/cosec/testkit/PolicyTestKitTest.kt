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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class PolicyTestKitTest {
    private val kit = PolicyTestKit.load(Path.of("src", "test", "resources", "example", "documents"))

    /** The example a user copies: the documents of a directory, a test for each scenario file. */
    @TestFactory
    fun example(): List<DynamicTest> = kit.tests(Path.of("src", "test", "resources", "example", "scenarios"))

    @Test
    fun `the example names its scenarios by their path`() {
        kit.tests(Path.of("src", "test", "resources", "example", "scenarios")).map { it.displayName }.assert()
            .contains("west/a-reader-of-acme-west-reads.json", "a-reader-reads-an-order.json")
    }

    @Test
    fun `a scenario whose expect does not hold fails, naming it`() {
        val error = assertThrows<AssertionError> {
            kit.check("reader-deletes", scenario("""{"decision": "Permit"}""", action = "order:delete"))
        }
        error.message.assert().startsWith("reader-deletes:").contains("\"decision\":\"Deny\"")
    }

    @Test
    fun `a by the expect names is compared, one it leaves out is not`() {
        kit.check("any statement", scenario("""{"decision": "Permit"}"""))
        assertThrows<AssertionError> {
            kit.check(
                "another statement",
                scenario(
                    """{"decision": "Permit", "by": {"policy": "no-delete", "statement": 0,
                       "via": {"binding": "g:no-delete", "document": "tenant"}}}"""
                ),
            )
        }
    }

    @Test
    fun `evaluate answers the decision without an expect`() {
        val outcome = kit.evaluate("""{"credential": {"tenant": "acme", "roles": ["reader"]}}""")
        (outcome as EngineOutcome.Decided).decision.assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `a scenario of the kit has no documents of its own`() {
        assertThrows<ScenarioFormatException> {
            kit.evaluate("""{"documents": {"platform": {}}}""")
        }
    }

    @Test
    fun `a misspelt field is an error, not ignored`() {
        assertThrows<ScenarioFormatException> {
            kit.evaluate("""{"request": {"actoin": "order:read"}}""")
        }
    }

    @Test
    fun `a document that does not compile fails loading`() {
        val error = assertThrows<DocumentException> {
            PolicyTestKit.of(
                """{"policies": [{"id": "p", "statements": [{"effect": "allow", "action": "order:read"}]}],
                   "bindings": [{"id": "b", "subject": "*", "app": "*", "space": "*", "policy": "missing"}]}"""
            )
        }
        error.message.assert().startsWith("The platform document:")
    }

    @Test
    fun `a document compiles as published, so the suite's fault field is rejected`() {
        assertThrows<DocumentException> {
            PolicyTestKit.of(
                """{"policies": [{"id": "p", "fault": "boom", "statements": [{"effect": "allow", "action": "*"}]}]}"""
            )
        }
    }

    @Test
    fun `a directory without scenarios is an error, so a mistyped path does not pass`() {
        val empty = Files.createTempDirectory("scenarios")
        try {
            assertThrows<IllegalArgumentException> { kit.tests(empty) }
            assertThrows<IllegalArgumentException> { kit.tests(empty.resolve("missing")) }
        } finally {
            Files.delete(empty)
        }
    }

    @Test
    fun `a directory without documents is an error`() {
        assertThrows<IllegalArgumentException> { PolicyTestKit.load(Path.of("src", "test", "resources", "missing")) }
    }

    /** The kit compiles as an instance does: against the platform's declared attributes and its limits. */
    @Test
    fun `documents compile against the platform's attributes and limits`() {
        val platform = """{"limits": {"maxInValues": 2}, "attributes": [{"path": "ext.geo.region", "type": "string"}]}"""
        val tenant = { condition: String ->
            """{"policies": [{"id": "p", "statements": [{"effect": "allow", "action": "*", "condition": $condition}]}]}"""
        }
        // An attribute the platform declares compiles in a tenant document; undeclared, it does not.
        PolicyTestKit.of(platform, tenants = mapOf("acme" to tenant("""{"eq": ["ext.geo.region", "eu"]}""")))
        assertThrows<DocumentException> {
            PolicyTestKit.of("{}", tenants = mapOf("acme" to tenant("""{"eq": ["ext.geo.region", "eu"]}""")))
        }.message.assert().startsWith("The tenant acme document:")
        // The platform's stricter limit holds for the other documents.
        val wide = tenant("""{"in": ["scope.app", ["a", "b", "c"]]}""")
        PolicyTestKit.of("{}", tenants = mapOf("acme" to wide))
        assertThrows<DocumentException> { PolicyTestKit.of(platform, tenants = mapOf("acme" to wide)) }
            .message.assert().startsWith("The tenant acme document:")
    }

    private fun scenario(expect: String, action: String = "order:read") = ScenarioJson.parse(
        """{"credential": {"tenant": "acme", "roles": ["reader"]}, "request": {"action": "$action"}, "expect": $expect}"""
    )
}
