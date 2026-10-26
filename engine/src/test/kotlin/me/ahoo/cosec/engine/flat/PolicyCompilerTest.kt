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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** Fail closed (RFC 0005 §9.1): what does not compile is an error with its path, never a statement that matches more. */
class PolicyCompilerTest {
    private val compiler = PolicyCompiler()
    private val id = PolicyId("p")

    private fun errors(condition: Condition? = null, vararg statements: StatementSource): List<CompileError> =
        try {
            compiler.compile(id, condition, statements.toList())
            throw AssertionError("compiled")
        } catch (e: CompileException) {
            e.errors
        }

    private fun eq(attribute: String, value: String) = Condition.Eq(attribute, Literal.Str(value))

    @Test
    fun `a statement compiles its patterns, condition and index keys`() {
        val statement = compiler.statement(
            id,
            0,
            StatementSource(
                "read",
                Effect.ALLOW,
                listOf("order:read", "http:GET"),
                listOf(ResourceSource("order/{id}"), ResourceSource("http:path:/orders/{id}", caseSensitive = false)),
                Condition.And(
                    listOf(
                        eq("resource.var.id", "1"),
                        Condition.In("scope.app", listOf(Literal.Str("a"), Literal.Str("b")))
                    )
                ),
            ),
        )
        statement.name.assert().isEqualTo("read")
        statement.actionPatterns.assert().containsExactly(
            ActionPattern.parse("order:read"),
            ActionPattern.parse("http:GET")
        )
        statement.resourcePatterns.assert()
            .containsExactly(
                ResourcePattern.parse("order/{id}"),
                ResourcePattern.parse("http:path:/orders/{id}", false)
            )
        statement.captures.assert().isTrue()
        statement.keys.toList().assert().containsExactly(
            IndexKeys.of(statement.condition!!, Attribute.of(Attribute.Kind.SCOPE_APP))
        )
        statement.toString().assert().contains("ALLOW read")
    }

    @Test
    fun `a statement without resources matches any resource and captures nothing`() {
        val statement = compiler.statement(id, 0, StatementSource("all", Effect.DENY, listOf("*")))
        statement.resourcePatterns.assert().containsExactly(ResourcePattern.AnyResource)
        statement.captures.assert().isFalse()
        statement.keys.assert().isEmpty()
    }

    @Test
    fun `a statement needs an action`() {
        errors(null, StatementSource("s", Effect.ALLOW, emptyList())).assert()
            .containsExactly(
                CompileError("$.statements[0].action", "at least one action is required; write \"*\" for every action")
            )
    }

    @Test
    fun `invalid patterns are errors at their position`() {
        val errors = errors(
            null,
            StatementSource("ok", Effect.ALLOW, listOf("*")),
            StatementSource(
                "bad",
                Effect.ALLOW,
                listOf("order:read", "order"),
                listOf(ResourceSource("http:path:/a/{id:\\d+}"), ResourceSource("order/{id}", caseSensitive = false)),
            ),
        )
        errors.map { it.path }.assert()
            .containsExactly("$.statements[1].action[1]", "$.statements[1].resource[0]", "$.statements[1].resource[1]")
    }

    @Test
    fun `condition errors carry the statement's path`() {
        val errors = errors(
            eq("nowhere.at.all", "x"),
            StatementSource(
                "s",
                Effect.ALLOW,
                listOf("*"),
                condition = Condition.And(listOf(eq("scope.app", "a"), eq("subject.authenticated", "yes")))
            ),
        )
        errors.map { it.path }.assert().containsExactly("$.condition.eq[0]", "$.statements[0].condition.and[1].eq[1]")
    }

    @Test
    fun `condition limits apply`() {
        val tight = PolicyCompiler(ConditionChecker(ConditionLimits(maxNodes = 2)))
        val condition = Condition.And(listOf(eq("scope.app", "a"), eq("scope.space", "b")))
        assertThrownBy<CompileException> {
            tight.compile(id, null, listOf(StatementSource("s", Effect.ALLOW, listOf("*"), condition = condition)))
        }.hasMessageContaining("Policy [p] does not compile: $.statements[0].condition")
    }

    @Test
    fun `a condition reads only variables every resource pattern captures`() {
        val reads = eq("resource.var.id", "1")
        errors(null, StatementSource("s", Effect.ALLOW, listOf("*"), condition = reads)).assert()
            .containsExactly(
                CompileError(
                    "$.statements[0].resource",
                    "the condition reads resource.var.id, which [*] does not capture"
                )
            )
        val partly = StatementSource(
            "s",
            Effect.ALLOW,
            listOf("*"),
            listOf(ResourceSource("order/{id}"), ResourceSource("order/{other}")),
            reads,
        )
        errors(null, partly).map { it.path }.assert().containsExactly("$.statements[0].resource[1]")
    }

    @Test
    fun `a policy condition cannot read captured variables`() {
        errors(eq("resource.var.id", "1")).single().message.assert().contains("cannot read resource.var.id")
        assertThrownBy<CompileException> { compiler.condition(id, eq("resource.var.id", "1")) }
        assertThrownBy<CompileException> { compiler.condition(id, eq("unknown", "1")) }
        assertThrownBy<CompileException> { compiler.statement(id, 0, StatementSource("s", Effect.ALLOW, emptyList())) }
        // Nor one built by hand from a statement's condition.
        val capturing = compiler.statement(
            id,
            0,
            StatementSource(
                "s",
                Effect.ALLOW,
                listOf("*"),
                listOf(ResourceSource("order/{id}")),
                eq("resource.var.id", "1")
            ),
        )
        assertThrownBy<IllegalArgumentException> { CompiledPolicy(id, capturing.condition, emptyList()) }
    }

    @Test
    fun `statements keep their positions`() {
        val statement = compiler.statement(id, 1, StatementSource("s", Effect.ALLOW, listOf("*")))
        assertThrownBy<IllegalArgumentException> { CompiledPolicy(id, null, listOf(statement)) }
            .hasMessageContaining("is at 0, not at its index 1")
    }

    @Test
    fun `index options take only attributes the engine reads itself`() {
        assertThrownBy<IllegalArgumentException> {
            IndexOptions(attributes = listOf(Attribute.of(Attribute.Kind.HTTP_HEADER, "x")))
        }
        assertThrownBy<IllegalArgumentException> { IndexOptions(minStatements = -1) }
        IndexOptions(
            attributes = listOf(Attribute.of(Attribute.Kind.RESOURCE_ATTRIBUTE, "owner"))
        ).attributes.assert().hasSize(1)
    }
}
