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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.control.grant.GrantFixtures.ACME
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import kotlin.random.Random

/**
 * Random snapshots, grantors, statements and requests over small pools, so that values collide: the same roles in
 * several spaces, conditions that read the grantor's attributes, variables, overlapping resources. Narrowed
 * statements are built from held ones, so that the analysis's YES branch is reached often.
 *
 * The four ways the analysis once read the grantor's rights differently from the engine are all reachable (RC review
 * §2): attributes of other JSON types than strings (RC1-2), bindings that expire (RC1-3), app documents that bind the
 * grantor's roles (RC1-4), and platform policies with a tenant policy's id, which a removal re-points bindings to
 * (RC1-9).
 */
class GrantGenerator(private val random: Random) {
    val apps = listOf("order", "user").map(::AppId)
    val spaces = listOf(SpaceId.DEFAULT, SpaceId("team-a"), SpaceId("team-b"), SpaceId("team-c"))
    val roles = listOf("ops", "dev", "viewer").map(::RoleId)
    val principals = listOf("gina", "hal", "ivy")
    private val actions =
        listOf("*", "order:*", "order:read", "order:delete", "user:*", "user:read", "http:*", "http:GET")
    private val concreteActions =
        listOf(
            "order" to "read",
            "order" to "delete",
            "order" to "list",
            "user" to "read",
            "http" to "GET",
            "http" to "POST"
        )
    private val resources = listOf(
        "order/**", "order/*", "order/{v0}", "order/a", "order/{v0}/**", "order/a/{v1}", "order/{v0}/x", "user/**",
        "user/{v0}", "http:path:/orders/{v0}", "http:path:/orders/**", "http:path:/orders/a", "http:path:/orders/{v0}/x",
    )

    /** For each resource pattern, patterns it contains (and some it does not), to narrow to. */
    private val narrower = mapOf(
        "order/**" to listOf("order/*", "order/a", "order/{v0}", "order/a/{v1}", "order/{v0}/**", "order", "order/{v0}/x"),
        "order/*" to listOf("order/a", "order/{v0}", "order/*"),
        "order/{v0}" to listOf("order/a", "order/*", "order/{v0}", "order/{v1}"),
        "order/{v0}/**" to listOf("order/a/{v1}", "order/{v0}/x", "order/{v0}", "order/{v1}/y"),
        "user/**" to listOf("user/{v0}", "user/a"),
        "http:path:/orders/**" to listOf("http:path:/orders/{v0}", "http:path:/orders/a", "http:path:/orders/{v0}/x", "http:path:/orders"),
        "http:path:/orders/{v0}" to listOf("http:path:/orders/a", "http:path:/orders/{v1}", "http:path:/orders/*"),
        "*" to listOf("order/**", "order/{v0}", "http:path:/orders/{v0}"),
    )
    private val values = listOf("a", "b", "x", "y", "gina", "hal", "A", "%2F", "K", "K", "", "a;b", "..")
    private val ips = listOf("10.1.2.3", "10.2.0.1", "192.168.0.1").map { IpAddress.parse(it)!! } + listOf(null)

    private fun <T> pick(list: List<T>): T = list[random.nextInt(list.size)]

    fun subject(id: String = pick(principals)): OracleSubject = OracleSubject(
        tenant = ACME,
        id = id,
        memberships = Memberships(
            Memberships.Source.CLAIMS,
            spaces.filter { random.nextInt(3) == 0 }.associateWith { roles.filter { random.nextInt(3) == 0 }.toSet() },
            if (random.nextInt(4) == 0) listOf(PolicyId("p0")) else emptyList(),
        ),
        attributes = buildMap {
            if (random.nextBoolean()) put("region", pick(listOf("eu", "us")))
            if (random.nextInt(3) == 0) put("suspended", random.nextBoolean())
            if (random.nextInt(3) == 0) put("level", random.nextInt(1, 4))
        },
    )

    fun grantor(subject: OracleSubject): Grantor =
        Grantor(subject.tenant, PrincipalId(subject.id), subject.memberships, subject.attributes, true)

    fun atom(): Condition = when (val kind = random.nextInt(14)) {
        0 -> Condition.Eq("scope.space", Literal.Str(pick(spaces).value))
        1 -> Condition.In(
            "scope.space",
            spaces.filter { random.nextBoolean() }.ifEmpty { spaces }.map { Literal.Str(it.value) }
        )
        2 -> Condition.Eq("scope.app", Literal.Str(pick(apps).value))
        3 -> Condition.Cidr("environment.ip", listOf(Literal.Str(pick(listOf("10.0.0.0/8", "10.1.0.0/16")))))
        4 -> Condition.Eq("subject.attributes.region", Literal.Str(pick(listOf("eu", "us"))))
        5 -> Condition.Eq("resource.var.${pick(listOf("v0", "v1"))}", Operand.Attr("subject.id"))
        6 -> Condition.Contains(Literal.Str(pick(roles).value), "scope.roles")
        7 -> Condition.Contains(Literal.Str(pick(roles).value), "subject.roles")
        8 -> Condition.Eq("http.header.x-k", Literal.Str("1"))
        9 -> Condition.Eq("resource.var.v0", Literal.Str(pick(values)))
        in 10..12 -> typedAttribute(kind)
        else -> Condition.Eq("subject.id", Literal.Str(pick(principals)))
    }

    /** An atom over an attribute of another type than strings, which reads as its toString() (RC1-2). */
    private fun typedAttribute(kind: Int): Condition = when (kind) {
        10 -> Condition.Not(Condition.Eq("subject.attributes.suspended", Literal.Str("true")))
        11 -> Condition.Eq("subject.attributes.level", Literal.Str(pick(listOf("1", "2", "3"))))
        else -> Condition.Exists("subject.attributes.${pick(listOf("suspended", "level"))}")
    }

    /** No expiry most of the time, else one of a few times after [GrantFixtures.NOW] (RC1-3). */
    fun expiry(): Long? = if (random.nextInt(3) == 0) pick(EXPIRIES) else null

    /** A time a request is made at before [until]: now, or after some of the [expiry] times; `null` if none is. */
    fun time(until: Long? = null): Long? = TIMES.filter { until == null || it < until }.randomOrNull(random)

    fun condition(): Condition? = when (random.nextInt(6)) {
        0, 1 -> null
        2 -> Condition.Not(atom())
        3 -> Condition.And(listOf(atom(), atom()))
        4 -> Condition.Or(listOf(atom(), atom()))
        else -> atom()
    }

    fun statement(effect: Effect = if (random.nextInt(4) == 0) Effect.DENY else Effect.ALLOW): GrantStatement =
        GrantStatement(
            effect = effect,
            actions = List(random.nextInt(1, 3)) { ActionPattern.parse(pick(actions)) }.distinct(),
            resources = if (random.nextInt(5) == 0) {
                listOf(ResourcePattern.AnyResource)
            } else {
                List(random.nextInt(1, 3)) { ResourcePattern.parse(pick(resources)) }.distinct()
            },
            condition = condition(),
        )

    /** A statement that [held] contains, most of the time: narrower actions and resources, a stronger condition. */
    fun narrow(held: GrantStatement, effect: Effect = held.effect): GrantStatement {
        val actions = held.actions.map { action ->
            when {
                random.nextInt(6) == 0 -> ActionPattern.parse(pick(actions))
                action.service == null -> ActionPattern.parse(pick(actions))
                action.verb == null -> ActionPattern.parse("${action.service}:${pick(listOf("read", "delete", "*"))}")
                else -> action
            }
        }.distinct()
        val resources = held.resources.map { resource ->
            val options = narrower[resource.toString()]
            if (options == null || random.nextInt(5) == 0) resource else ResourcePattern.parse(pick(options))
        }.distinct()
        val condition = when (random.nextInt(4)) {
            0 -> held.condition
            1 -> held.condition?.let { Condition.And(listOf(it, atom())) } ?: atom()
            2 -> condition()
            else -> held.condition?.let { Condition.And(listOf(atom(), it)) }
        }
        return GrantStatement(effect, actions, resources, condition)
    }

    fun scope(): BindingScope = BindingScope(
        if (random.nextInt(3) == 0) Match.Exactly(pick(apps)) else Match.Any,
        if (random.nextInt(2) == 0) Match.Exactly(pick(spaces)) else Match.Any,
    )

    fun subjectRef(): SubjectRef = when (random.nextInt(6)) {
        0 -> SubjectRef.Anyone
        1, 2 -> SubjectRef.Principal(ACME, PrincipalId(pick(principals)))
        else -> SubjectRef.Role(ACME, pick(roles))
    }

    /** A tenant document of a few policies and bindings; policy `p0` is sometimes attachable. */
    fun document(
        prefix: String,
        policies: Int = random.nextInt(1, 4),
        bindings: Int = random.nextInt(1, 5)
    ): GrantDocument {
        val list = (0 until policies).map { index ->
            GrantPolicy(
                PolicyId("$prefix$index"),
                List(random.nextInt(1, 3)) { statement() },
                condition = if (random.nextInt(6) == 0) atom() else null,
                attachable = index == 0 && random.nextInt(3) == 0,
            )
        }
        val bound = (0 until bindings).map { index ->
            Binding(BindingId("$prefix-b$index"), subjectRef(), scope(), list[random.nextInt(list.size)].id, expiry())
        }
        return GrantDocument(list, bound)
    }

    /**
     * An app document: its permission policies `perm:<app>/...`, ALLOWs and DENYs, bound to roles of every tenant or
     * of acme. The analysis must read it whether or not a change names the app (RC1-4).
     */
    fun app(app: AppId): GrantDocument {
        val list = (0 until random.nextInt(1, 3)).map { index ->
            GrantPolicy(PolicyId("perm:${app.value}/p$index"), List(random.nextInt(1, 3)) { statement() })
        }
        val bound = (0 until random.nextInt(1, 3)).map { index ->
            val role = SubjectRef.Role(if (random.nextBoolean()) null else ACME, pick(roles))
            val scope = BindingScope(
                Match.Exactly(app),
                if (random.nextBoolean()) Match.Exactly(pick(spaces)) else Match.Any
            )
            Binding(BindingId("${app.value}-b$index"), role, scope, list[random.nextInt(list.size)].id, expiry())
        }
        return GrantDocument(list, bound)
    }

    fun snapshot(): GrantSnapshot {
        val policies = ArrayList<GrantPolicy>()
        val bindings = ArrayList<Binding>()
        if (random.nextInt(3) == 0) {
            policies += GrantPolicy(PolicyId("platform-deny"), listOf(statement(Effect.DENY)))
            bindings += Binding(BindingId("pd"), SubjectRef.Anyone, scope(), PolicyId("platform-deny"))
        }
        if (random.nextInt(4) == 0) {
            // A platform policy with one of the tenant's ids: ambiguous until the tenant removes its own (RC1-9).
            policies += GrantPolicy(
                PolicyId("p${random.nextInt(3)}"),
                List(random.nextInt(1, 3)) { statement() },
                attachable = random.nextInt(3) == 0,
            )
        }
        val apps = apps.filter { random.nextInt(3) == 0 }.associateWith(::app)
        return GrantSnapshot(
            platform = GrantDocument(policies, bindings),
            apps = apps,
            tenants = mapOf(ACME to document("p")),
            now = GrantFixtures.NOW,
        )
    }

    /** A request that matches [action] and [resource] as often as values allow. */
    fun request(
        subject: OracleSubject,
        scope: BindingScope,
        action: ActionPattern,
        resource: ResourcePattern
    ): OracleRequest {
        val app = (scope.app as? Match.Exactly)?.value ?: pick(apps)
        val space = (scope.space as? Match.Exactly)?.value ?: pick(spaces)
        return OracleRequest(
            subject = subject,
            app = app,
            space = space,
            action = action(action),
            resource = resource(resource),
            ip = pick(ips),
            headers = if (random.nextBoolean()) mapOf("x-k" to "1") else emptyMap(),
        )
    }

    private fun action(pattern: ActionPattern): Action {
        val (service, verb) = pick(concreteActions)
        return Action(pattern.service ?: service, pattern.verb ?: verb)
    }

    private fun resource(pattern: ResourcePattern): Resource {
        val text = when (pattern) {
            ResourcePattern.AnyResource -> pick(resources)
            else -> pattern.toString()
        }
        val http = text.startsWith(ResourcePattern.HTTP_PREFIX)
        val parts = text.removePrefix(ResourcePattern.HTTP_PREFIX).split('/')
        val out = parts.map { part ->
            when {
                part == "**" || part.startsWith("{*") -> List(random.nextInt(3)) { pick(values) }.joinToString("/")
                part == "*" || part.startsWith('{') -> pick(values)
                random.nextInt(10) == 0 -> pick(values)
                else -> part
            }
        }
        return if (http) {
            Resource(ResourcePattern.HTTP_TYPE, out.joinToString("/"), emptyMap())
        } else {
            Resource(out.first(), out.drop(1).joinToString("/"), emptyMap())
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
        val EXPIRIES = listOf(GrantFixtures.NOW + HOUR, GrantFixtures.NOW + 2 * HOUR, GrantFixtures.NOW + 3 * HOUR)
        val TIMES = listOf(
            GrantFixtures.NOW,
            GrantFixtures.NOW + HOUR / 2,
            GrantFixtures.NOW + HOUR + HOUR / 2,
            GrantFixtures.NOW + 2 * HOUR + HOUR / 2,
            GrantFixtures.NOW + 4 * HOUR,
        )
    }
}
