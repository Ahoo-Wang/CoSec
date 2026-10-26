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

import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The partitions of [StatementIndex] (RFC 0005 §6.2): what a lookup finds, by statement name. */
class StatementIndexTest {
    private val compiler = PolicyCompiler(options = IndexOptions(minStatements = 0, conditionThreshold = 0))

    private fun statement(name: String, actions: List<String>, resources: List<ResourceSource> = emptyList(), condition: Condition? = null) =
        StatementSource(name, Effect.ALLOW, actions, resources, condition)

    private fun http(pattern: String, caseSensitive: Boolean = true, decode: Boolean = true) =
        ResourceSource("http:path:$pattern", caseSensitive, decode)

    private fun find(
        statements: List<StatementSource>,
        action: Action,
        resource: Resource,
        values: Map<Attribute.Kind, String> = emptyMap(),
    ): List<String> {
        val policy = compiler.compile(PolicyId("p"), null, statements)
        val lookup = Lookup(action, resource, values)
        policy.index(Effect.ALLOW)!!.lookup(lookup)
        return lookup.positions.map { policy.allows[it].name }
    }

    private class Lookup(
        override val action: Action,
        private val resource: Resource,
        private val values: Map<Attribute.Kind, String>,
    ) : IndexLookup {
        val positions = sortedSetOf<Int>()

        override val resourceType: String get() = resource.type

        override fun decodedPath(): HttpPath? = runCatching { ResourceTarget(resource).httpPath(true) }.getOrNull()

        override fun indexValue(attribute: Attribute): String? = values[attribute.kind]

        override fun add(positions: IntArray) {
            positions.forEach { this.positions += it }
        }
    }

    private val get = Action("http", "GET")

    private fun path(path: String) = Resource("http", path, emptyMap())

    @Test
    fun `actions partition by service, then verb`() {
        val statements = listOf(
            statement("any", listOf("*")),
            statement("order", listOf("order:*")),
            statement("read", listOf("order:read")),
            statement("write", listOf("order:write")),
            statement("both", listOf("order:read", "order:*")),
            statement("user", listOf("user:read")),
        )
        find(statements, Action("order", "read"), Resource("order", "1", emptyMap())).assert()
            .containsExactly("any", "order", "read", "both")
        find(statements, Action("user", "write"), Resource("order", "1", emptyMap())).assert().containsExactly("any")
    }

    @Test
    fun `resources partition by type`() {
        val statements = listOf(
            statement("any", listOf("*")),
            statement("order", listOf("*"), listOf(ResourceSource("order/{id}"))),
            statement("user", listOf("*"), listOf(ResourceSource("user/**"))),
            statement("http", listOf("*"), listOf(http("/orders/**"))),
        )
        find(statements, Action("order", "read"), Resource("order", "1", emptyMap())).assert().containsExactly("any", "order")
        find(statements, get, path("/orders/1")).assert().containsExactly("any", "http")
    }

    @Test
    fun `http patterns are found by their literal start`() {
        val statements = listOf(
            statement("root", listOf("*"), listOf(http("/"))),
            statement("orders", listOf("*"), listOf(http("/orders/**"))),
            statement("order", listOf("*"), listOf(http("/orders/{id}"))),
            statement("items", listOf("*"), listOf(http("/orders/{id}/items"))),
            statement("users", listOf("*"), listOf(http("/users/{id}"))),
            statement("leading", listOf("*"), listOf(http("/**/*.css"))),
            statement("glob", listOf("*"), listOf(http("/ord*/x"))),
            statement("folded", listOf("*"), listOf(http("/Orders/1", caseSensitive = false))),
            statement("raw", listOf("*"), listOf(http("/orders/1", decode = false))),
            statement("double", listOf("*"), listOf(http("//orders"))),
        )
        find(statements, get, path("/orders/1")).assert()
            .containsExactly("root", "orders", "order", "items", "leading", "glob", "folded", "raw")
        find(statements, get, path("/users/1")).assert().containsExactly("root", "users", "leading", "glob", "folded", "raw")
        find(statements, get, path("//orders")).assert().containsExactly("root", "leading", "glob", "folded", "raw", "double")
        find(statements, get, path("")).assert().containsExactly("leading", "raw")
        // A path that does not decode rules nothing out.
        find(statements, get, path("/orders/%zz")).assert().hasSize(statements.size)
    }

    @Test
    fun `a large partition splits by the attribute most statements constrain`() {
        fun app(value: String, ignoreCase: Boolean = false) = Condition.Eq("scope.app", Literal.Str(value), ignoreCase)
        val statements = listOf(
            statement("a", listOf("*"), condition = app("a")),
            statement("A", listOf("*"), condition = app("A", ignoreCase = true)),
            statement("in", listOf("*"), condition = Condition.In("scope.app", listOf(Literal.Str("a"), Literal.Str("b")))),
            statement("b", listOf("*"), condition = app("b")),
            statement("free", listOf("*"), condition = Condition.Not(app("a"))),
            statement("never", listOf("*"), condition = Condition.And(listOf(app("a"), app("b")))),
            statement("space", listOf("*"), condition = Condition.Eq("scope.space", Literal.Str("s"))),
        )
        val resource = Resource("order", "1", emptyMap())
        val action = Action("order", "read")
        find(statements, action, resource, mapOf(Attribute.Kind.SCOPE_APP to "a")).assert()
            .containsExactly("a", "A", "in", "free", "space")
        find(statements, action, resource, mapOf(Attribute.Kind.SCOPE_APP to "A")).assert().containsExactly("A", "free", "space")
        find(statements, action, resource).assert().containsExactly("free", "space")
    }

    @Test
    fun `a small partition is not split`() {
        val policy = PolicyCompiler(options = IndexOptions(minStatements = 0))
            .compile(PolicyId("p"), null, listOf(statement("a", listOf("*"), condition = Condition.Eq("scope.app", Literal.Str("a")))))
        val lookup = Lookup(get, path("/"), emptyMap())
        policy.index(Effect.ALLOW)!!.lookup(lookup)
        lookup.positions.assert().containsExactly(0)
        policy.index(Effect.DENY).assert().isNull()
    }

    @Test
    fun `few statements are scanned`() {
        val policy = PolicyCompiler().compile(PolicyId("p"), null, List(7) { statement("s$it", listOf("*")) })
        policy.index(Effect.ALLOW).assert().isNull()
        PolicyCompiler().compile(PolicyId("p"), null, List(8) { statement("s$it", listOf("*")) })
            .index(Effect.ALLOW).assert().isNotNull()
    }

    @Test
    fun `folding is ASCII only`() {
        StatementIndex.foldAscii("abc").assert().isSameAs("abc")
        StatementIndex.foldAscii("aBC-Ä").assert().isEqualTo("abc-Ä")
    }
}
