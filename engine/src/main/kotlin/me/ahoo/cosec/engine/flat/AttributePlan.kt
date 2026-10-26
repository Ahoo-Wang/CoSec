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

import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.decision.BindingRef

/**
 * The external attributes one evaluation may read (RFC 0006 §5.4.2): those of the candidate statements, the
 * index's candidates intersected with the applicable bindings, that pass their action and index keys, and of their
 * policies' conditions. Evaluation reads no external attribute outside it (P14); one it would read anyway is missing.
 */
class AttributePlan internal constructor(val attributes: List<Attribute>) {
    val isEmpty: Boolean get() = attributes.isEmpty()

    override fun toString(): String = "AttributePlan$attributes"

    companion object {
        val EMPTY: AttributePlan = AttributePlan(emptyList())
    }
}

/**
 * Plans an evaluation: walks the same candidates as [FlatEvaluator], in both passes, and collects the external
 * attributes of every statement that passes what [FlatEvaluator.step] checks before calling host code. A superset of
 * what the evaluation reads: it reads a statement's condition only after those checks, and a policy condition only for
 * such a statement.
 */
internal class FlatPlanner(request: FlatRequest, view: SnapshotView) : FlatEvaluator(request, view) {
    private var found: LinkedHashSet<Attribute>? = null

    fun plan(): AttributePlan {
        pass = Effect.DENY
        enumerate()
        pass = Effect.ALLOW
        enumerate()
        return found?.let { AttributePlan(it.toList()) } ?: AttributePlan.EMPTY
    }

    override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
        if (!policy.readsExternal) {
            return false
        }
        var reached = false
        for (statement in policy.statements(pass)) {
            if (statement.matchesAction(authz.action) && statement.admits(this)) {
                reached = true
                add(statement.externals)
            }
        }
        if (reached) {
            add(policy.externals)
        }
        return false
    }

    private fun add(attributes: Array<Attribute>) {
        if (attributes.isEmpty()) {
            return
        }
        val found = found ?: LinkedHashSet<Attribute>().also { found = it }
        found.addAll(attributes)
    }
}
