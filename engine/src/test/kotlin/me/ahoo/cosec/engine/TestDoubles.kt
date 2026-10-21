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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject

/** The matchers each evaluation called, in order: `guard:<group id>` and `rule:<rule name>`. */
class CallLog {
    val calls = mutableListOf<String>()

    fun clear() = calls.clear()
}

enum class GuardBehavior { MATCH, MISS, THROW }

enum class RuleBehavior { MATCH, ACTION_MISS, CONDITION_MISS, THROW }

/** Thrown by test matchers; one instance per matcher, so decisions compare equal across evaluations. */
class MatcherException(message: String) : RuntimeException(message)

class TestGuard(private val groupId: String, val behavior: GuardBehavior, private val log: CallLog) : Guard {
    val error = MatcherException("guard $groupId")

    override fun matches(request: AuthzRequest): Boolean {
        log.calls += "guard:$groupId"
        return when (behavior) {
            GuardBehavior.MATCH -> true
            GuardBehavior.MISS -> false
            GuardBehavior.THROW -> throw error
        }
    }
}

class TestRule(
    override val name: String,
    override val effect: Effect,
    override val index: Int,
    override val id: String?,
    val behavior: RuleBehavior,
    private val log: CallLog,
) : Rule {
    val error = MatcherException("rule $name")

    override fun match(request: AuthzRequest): RuleMatch {
        log.calls += "rule:$name"
        return when (behavior) {
            RuleBehavior.MATCH -> RuleMatch.MATCHED
            RuleBehavior.ACTION_MISS -> RuleMatch.ACTION_MISS
            RuleBehavior.CONDITION_MISS -> RuleMatch.CONDITION_MISS
            RuleBehavior.THROW -> throw error
        }
    }
}

/** An entry as a group visits it: [via] is the granting role in the role layer. */
class Grant(val rule: RuleEntry, val via: String? = null)

class TestGroup(override val id: String, override val guard: Guard, val grants: List<Grant>) : RuleGroup {
    override fun forEachRule(visitor: RuleVisitor): Boolean {
        for (grant in grants) {
            if (visitor.visit(grant.rule, grant.via)) {
                return true
            }
        }
        return false
    }
}

fun request(blocked: Boolean = false, root: Boolean = false): AuthzRequest =
    AuthzRequest(
        subject = Subject(
            id = "user",
            tenantId = "tenant",
            authenticated = true,
            roles = setOf("admin"),
            policies = setOf("p"),
            attributes = emptyMap(),
            root = root,
        ),
        scope = Scope.declared("tenant", "app", "default"),
        action = Action("http", "GET"),
        resource = Resource("http", "/orders/1", emptyMap()),
        environment = Environment(remoteIp = "127.0.0.1", requestId = "req", deviceId = "dev", blocked = blocked),
    )

/** Builds test policy sets whose matchers all write to one [log]. */
class Fixture(val log: CallLog = CallLog()) {
    fun guard(groupId: String, behavior: GuardBehavior = GuardBehavior.MATCH) = TestGuard(groupId, behavior, log)

    fun rule(
        name: String,
        effect: Effect,
        behavior: RuleBehavior = RuleBehavior.MATCH,
        index: Int = 0,
        id: String? = null,
    ) = TestRule(name, effect, index, id, behavior, log)

    /** A policy whose statements are indexed in order. */
    fun policy(
        id: String,
        vararg statements: Pair<Effect, RuleBehavior>,
        guard: GuardBehavior = GuardBehavior.MATCH,
    ): TestGroup =
        TestGroup(
            id,
            guard(id, guard),
            statements.mapIndexed { index, (effect, behavior) -> Grant(rule("$id.$index", effect, behavior, index)) },
        )

    /** An app's permissions: each grant is (role, permission id, effect, behavior), indexed within its role. */
    fun app(
        id: String,
        vararg grants: RoleGrant,
        guard: GuardBehavior = GuardBehavior.MATCH,
    ): TestGroup {
        val positions = mutableMapOf<String, Int>()
        return TestGroup(
            id,
            guard(id, guard),
            grants.map {
                val index = positions.merge(it.role, 1, Int::plus)!! - 1
                val entry = if (it.unresolved) {
                    UnresolvedGrant(it.permission, MatcherException("unresolved ${it.role}.${it.permission}"))
                } else {
                    rule("${it.role}.${it.permission}", it.effect, it.behavior, index, it.permission)
                }
                Grant(entry, it.role)
            },
        )
    }
}

/** With [unresolved], a grant of a permission id the app does not define: [effect] and [behavior] are ignored. */
class RoleGrant(
    val role: String,
    val permission: String,
    val effect: Effect,
    val behavior: RuleBehavior = RuleBehavior.MATCH,
    val unresolved: Boolean = false,
) {
    companion object {
        fun unresolved(role: String, permission: String) = RoleGrant(role, permission, Effect.DENY, unresolved = true)
    }
}

fun loaded(kind: LayerKind, vararg groups: RuleGroup) = LayerInput.Loaded(kind, groups.toList())
