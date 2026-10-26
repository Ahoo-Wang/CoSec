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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef

/**
 * A policy ready to evaluate. Its statements are 7.x matchers behind the adapter contracts of RFC 0002 ([Rule],
 * [Guard]) until the condition AST replaces them (RFC 0005 PR-4).
 *
 * [guard] is the policy condition (for a permission policy, the app condition, RFC 0004 §5.1); null when there is
 * none. Within one evaluation it runs at most once, and only when one of the policy's statements is a candidate.
 * Policies that share a condition should share the [Guard] instance: it then runs once for all of them, as the app
 * condition does in 7.x.
 *
 * [statements] keep their order; each [Rule.index] is its position in the policy, and [Rule.id] is not read.
 * Each statement's effect is read here, once.
 */
class CompiledPolicy(
    id: PolicyId,
    val guard: Guard?,
    statements: List<Rule>,
    /** Whether a principal may attach it (RFC 0004 §4.4). Permission policies are not attachable. */
    val attachable: Boolean = false,
) {
    internal val id: String = id.value
    internal val denies: Array<Rule>
    internal val allows: Array<Rule>

    /** Allocated once, so attaching the policy allocates nothing per request. */
    internal val attached: BindingRef.Attached = BindingRef.Attached(id)

    init {
        val denies = ArrayList<Rule>()
        val allows = ArrayList<Rule>()
        for (statement in statements) {
            // A null effect from a Java adapter would otherwise belong to no pass and be skipped silently.
            when (statement.effect as Effect?) {
                Effect.DENY -> denies += statement
                Effect.ALLOW -> allows += statement
                null -> throw IllegalArgumentException(
                    "Statement ${statement.index} of policy ${id.value} has no effect"
                )
            }
        }
        this.denies = denies.toTypedArray()
        this.allows = allows.toTypedArray()
    }

    val policyId: PolicyId get() = PolicyId(id)

    internal fun statements(effect: Effect): Array<Rule> = if (effect === Effect.DENY) denies else allows
}

/** The policies every platform document has (RFC 0004 §4.7, §4.8). Documents can neither define nor replace them. */
object BuiltinPolicies {
    /** One DENY statement, matching a blocked request. The first candidate of every evaluation. Cannot be bound. */
    val BLACKLIST: PolicyId = PolicyId("cosec:blacklist")

    /** One ALLOW statement, matching everything. Only a break-glass binding may bind it, and it is not attachable. */
    val ROOT: PolicyId = PolicyId("cosec:root")

    internal val blacklist = CompiledPolicy(BLACKLIST, null, listOf(BlacklistRule))
    internal val root = CompiledPolicy(ROOT, null, listOf(RootRule))

    internal fun isBuiltin(id: String): Boolean = id == BLACKLIST.value || id == ROOT.value
}

private object BlacklistRule : Rule {
    override val effect: Effect = Effect.DENY
    override val index: Int = 0
    override val name: String = "blacklist"
    override val id: String? = null

    override fun match(request: AuthzRequest): RuleMatch =
        if (request.environment.blocked) RuleMatch.MATCHED else RuleMatch.CONDITION_MISS
}

private object RootRule : Rule {
    override val effect: Effect = Effect.ALLOW
    override val index: Int = 0
    override val name: String = "root"
    override val id: String? = null

    override fun match(request: AuthzRequest): RuleMatch = RuleMatch.MATCHED
}
