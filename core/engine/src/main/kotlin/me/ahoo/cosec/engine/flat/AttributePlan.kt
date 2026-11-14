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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.decision.BindingRef

/**
 * The external attributes one evaluation may read (RFC 0006 §5.4.2): those of the candidate statements, the
 * index's candidates intersected with the applicable bindings, that pass their action and index keys, and of their
 * policies' conditions; of the rate limit rules; and of the boundary that holds the request's tenant, its statements'
 * and its condition (RFC 0007 §7.6, #1208). Evaluation reads no external attribute outside it (P14, `ATR-8`); one it
 * would read anyway is missing.
 */
@InternalCoSecApi
class AttributePlan internal constructor(val attributes: List<Attribute>) {
    val isEmpty: Boolean get() = attributes.isEmpty()

    override fun toString(): String = "AttributePlan$attributes"

    @InternalCoSecApi
    companion object {
        val EMPTY: AttributePlan = AttributePlan(emptyList())
    }
}

/**
 * Plans an evaluation: walks the same candidates as [FlatEvaluator], in both passes, and in each the statements its
 * index gives, then the rate limit rules and the boundary a permit is held to, and collects the external attributes of
 * every statement that passes what [FlatEvaluator.step] checks before calling host code. A superset of what the
 * evaluation reads: it reads a statement's condition only after those checks, a policy condition only for such a
 * statement, and the boundary only on a permit.
 */
internal class FlatPlanner(request: FlatRequest, view: SnapshotView) : FlatEvaluator(request, view) {
    private var found: LinkedHashSet<Attribute>? = null

    fun plan(): AttributePlan {
        pass = Effect.DENY
        enumerate()
        pass = Effect.ALLOW
        enumerate()
        // A permit's obligations read the rate limit rules too (RFC 0006 §8.2).
        for (rules in listOfNotNull(view.platform.rateLimits, view.app?.rateLimits, view.tenant?.rateLimits)) {
            for (rule in rules) {
                if (rule.statement.matchesAction(authz.action) && rule.statement.admits(this)) {
                    add(rule.externals)
                }
            }
        }
        // A permit is held to the boundary of the request's tenant, which reads as an ALLOW does (RFC 0007 §7.6): the
        // tenant's own, else the platform's default; another boundary is not this request's (#1208).
        val boundary = view.boundaryOf(request.scope)?.let { view.platform.boundaryPolicy(it) }
        if (boundary != null && boundary.readsExternal) {
            collect(boundary, boundary.allows)
        }
        return found?.let { AttributePlan(it.toList()) } ?: AttributePlan.EMPTY
    }

    override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
        if (policy.readsExternal) {
            val statements = policy.statements(pass)
            // The statements the evaluation visits: those its index gives (I1), or all of them without one.
            val index = policy.index(pass)
            collect(policy, statements, if (index == null) null else candidates(index))
        }
        return false
    }

    /**
     * The attributes of [statements] that pass their action and index keys, and of [policy]'s condition if one does.
     * With [positions], only those statements: the others cannot match the request, so evaluation never reaches them.
     */
    private fun collect(policy: CompiledPolicy, statements: Array<CompiledStatement>, positions: IntArray? = null) {
        var reached = false
        if (positions == null) {
            for (statement in statements) {
                reached = reach(statement) || reached
            }
        } else {
            for (position in positions) {
                reached = reach(statements[position]) || reached
            }
        }
        if (reached) {
            add(policy.externals)
        }
    }

    /** Whether [statement] passes what [FlatEvaluator.step] checks before calling host code; then its attributes are planned. */
    private fun reach(statement: CompiledStatement): Boolean {
        if (!statement.matchesAction(authz.action) || !statement.admits(this)) {
            return false
        }
        add(statement.externals)
        return true
    }

    private fun add(attributes: Array<Attribute>) {
        if (attributes.isEmpty()) {
            return
        }
        val found = found ?: LinkedHashSet<Attribute>().also { found = it }
        // One by one: addAll(Array) wraps the array in a list on every call.
        for (attribute in attributes) {
            found.add(attribute)
        }
    }
}
