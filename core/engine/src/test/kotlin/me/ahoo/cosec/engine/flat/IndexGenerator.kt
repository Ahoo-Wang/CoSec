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

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import kotlin.random.Random

/**
 * Random policies with real action and resource patterns and conditions, and random requests, drawn from small
 * vocabularies so that patterns, index keys and requests collide often: every form of the `http` subset (literals,
 * `?`, globs, captures, `**` and `{*name}` at either end, case-insensitive and undecoded patterns), typed resources,
 * `service:verb`, and conditions on the index attributes with `ignoreCase`, `in`, `and`, `or` and `not`.
 *
 * Statement conditions start with a probe, `not(eq(request.attributes.s:<policy>.<index>, "never"))`, which is always
 * true and records that the condition was evaluated; policy conditions read the probe `g:<policy>`, whose value is
 * drawn per policy. [Counting] answers them and counts the reads.
 */
class IndexGenerator(private val random: Random) {
    private val actions = listOf(
        "*",
        "http:*",
        "http:GET",
        "http:POST",
        "order:*",
        "order:read",
        "order:write",
        "user:read"
    )
    private val httpPatterns = listOf(
        "/orders/**", "/orders/{id}", "/orders/*/items", "/orders/1", "/orders/{id}/items/{item}", "/users/{id}",
        "/**/*.css", "/{*rest}", "/a?c/x", "/ord*", "/orders/", "/", "/orders/{id}.json", "/**", "/orders/1/**",
        "/{*id}", "/**/items",
    )
    private val typedPatterns = listOf(
        "order",
        "order/{id}",
        "order/*",
        "order/**",
        "order/{id}/x",
        "user/{id}",
        "user"
    )
    private val apps = listOf("app", "other", "App")
    private val spaces = listOf("", "team", "Team")
    private val tenants = listOf("acme", "beta", "ACME")
    private val types = listOf("order", "user", "http", "Order")

    private fun <T> List<T>.pick(): T = this[random.nextInt(size)]

    private fun chance(percent: Int) = random.nextInt(100) < percent

    /** A DENY on a bare type does not compile, so a DENY gets the type and everything under it instead. */
    private fun resource(deny: Boolean): ResourceSource =
        if (chance(70)) {
            ResourceSource(
                "http:path:" + httpPatterns.pick(),
                caseSensitive = !chance(10),
                decodeAndParseSegments = !chance(10),
            )
        } else {
            ResourceSource(typedPatterns.pick().let { if (deny && '/' !in it) "$it/**" else it })
        }

    private fun str(value: String) = Literal.Str(value)

    /** A condition over the index attributes and, when every resource pattern captures `id`, `resource.var.id`. */
    private fun condition(depth: Int, capturesId: Boolean): Condition =
        when (random.nextInt(if (depth > 1) 7 else 10)) {
            0 -> Condition.Eq("scope.app", str(apps.pick()), ignoreCase = chance(30))
            1 -> Condition.In("scope.space", List(random.nextInt(1, 3)) { str(spaces.pick()) })
            2 -> Condition.Eq("subject.tenant", str(tenants.pick()), ignoreCase = chance(30))
            3 -> Condition.Eq("resource.type", str(types.pick()))
            4 -> if (capturesId) {
                Condition.Eq(
                    "resource.var.id",
                    str(listOf("1", "2").pick())
                )
            } else {
                Condition.Exists("scope.app")
            }
            5 -> Condition.Prefix("scope.app", str("ap"))
            6 -> Condition.In("resource.type", listOf(str("order"), str("user")))
            7 -> Condition.And(List(random.nextInt(1, 3)) { condition(depth + 1, capturesId) })
            8 -> Condition.Or(List(random.nextInt(1, 3)) { condition(depth + 1, capturesId) })
            else -> Condition.Not(condition(depth + 1, capturesId))
        }

    private fun statement(policy: String, index: Int): StatementSource {
        val effect = if (chance(35)) Effect.DENY else Effect.ALLOW
        val resources = List(random.nextInt(0, 3)) { resource(effect == Effect.DENY) }
        val capturesId = resources.isNotEmpty() && resources.all { source ->
            runCatching {
                ResourcePatterns.variables(source).contains("id")
            }.getOrDefault(false)
        }
        val probe = Condition.Not(Condition.Eq("request.attributes.s:$policy.$index", str("never")))
        val condition = if (chance(60)) Condition.And(listOf(probe, condition(0, capturesId))) else probe
        return StatementSource(
            "$policy.$index",
            effect,
            List(random.nextInt(1, 3)) { actions.pick() },
            resources,
            condition,
        )
    }

    /** A policy's sources: its condition, if any, reads the probe `g:<id>`. */
    class PolicySource(val id: String, val condition: Condition?, val statements: List<StatementSource>)

    fun policies(): List<PolicySource> =
        List(random.nextInt(1, 4)) { p ->
            val id = "p$p"
            val condition = if (chance(40)) Condition.Eq("request.attributes.g:$id", str("yes")) else null
            PolicySource(id, condition, List(random.nextInt(0, 30)) { statement(id, it) })
        }

    /** The values the probes `g:<id>` answer. */
    fun guardValues(policies: List<PolicySource>): Map<String, String> =
        policies.associate { "g:${it.id}" to if (chance(70)) "yes" else "no" }

    fun request(): FlatRequest {
        val http = chance(65)
        val resource = if (http) {
            val path = listOf(
                "/orders/1", "/orders/2/items", "/orders/1/items/9", "/users/1", "/a/b.css", "/abc/x", "/ORDERS/1",
                "/orders/", "/", "", "/orders/%zz", "/orders%2F1", "/orders/1.json", "/x/items", "/orders/1/x",
            ).pick()
            Resource("http", path, emptyMap())
        } else {
            val (type, id) = listOf(
                "order" to "1",
                "order" to "1/x",
                "order" to "",
                "user" to "2",
                "Order" to "1"
            ).pick()
            Resource(type, id, emptyMap())
        }
        val action = if (http) {
            Action("http", listOf("GET", "POST", "DELETE").pick())
        } else {
            listOf(Action("order", "read"), Action("order", "write"), Action("user", "read")).pick()
        }
        val tenant = tenants.pick()
        val space = spaces.pick()
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId(space) to setOf(RoleId("viewer"))),
            emptyList(),
        )
        val resolution = ScopeResolver.resolve(
            ScopeSubject(TenantId(tenant), authenticated = true, memberships),
            DeclaredScope(apps.pick(), space),
            HostScope(null),
        )
        val scope = (resolution as ScopeResolution.Resolved).scope
        val authz = AuthzRequest(
            Subject("alice", tenant, true, emptySet(), emptySet(), emptyMap(), false, true, true),
            Scope.declared(tenant, scope.app.value, space),
            action,
            resource,
            Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return FlatRequest(authz, scope, memberships, NOW)
    }

    /** Answers the probes and counts every read, by probe name. */
    class Counting(private val guards: Map<String, String>) : ConditionInput {
        val reads = LinkedHashMap<String, Int>()

        override fun string(attribute: Attribute): String? {
            reads.merge(attribute.name, 1, Int::plus)
            return guards[attribute.name] ?: "x"
        }

        override fun bool(attribute: Attribute): Boolean? = null

        override fun stringSet(attribute: Attribute): Set<String>? = null

        override fun ip(attribute: Attribute): IpAddress? = null

        override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
    }

    fun compile(policies: List<PolicySource>, options: IndexOptions): List<CompiledPolicy> {
        val compiler = PolicyCompiler(options = options)
        return policies.map { compiler.compile(PolicyId(it.id), it.condition, it.statements) }
    }
}

/** The variables a resource source captures. */
private object ResourcePatterns {
    fun variables(source: ResourceSource): List<String> =
        me.ahoo.cosec.engine.pattern.ResourcePattern.parse(
            source.pattern,
            source.caseSensitive,
            source.decodeAndParseSegments,
        ).variableNames
}
