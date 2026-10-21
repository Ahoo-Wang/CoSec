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

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.StatementRef

/**
 * The default [Engine]: stateless and thread-safe.
 *
 * Combines layer by layer (RFC 0002 §4.1): blacklist, root, then global, principal and role layers. Within a layer,
 * every guard runs before any rule, and every DENY rule before any ALLOW rule. A DENY or an error ends the evaluation;
 * the first ALLOW is kept while later layers are still checked for a DENY; nothing matched is an implicit deny.
 */
object CoSecEngine : Engine {
    internal val BLACKLIST: Decision = Decision.Deny(StatementRef.Blacklist)
    internal val ROOT: Decision = Decision.Permit(StatementRef.Root)
    internal val IMPLICIT_DENY: Decision = Decision.Deny(null)

    private val BLACKLIST_EVALUATION = Evaluation(BLACKLIST, null)
    private val ROOT_EVALUATION = Evaluation(ROOT, null)
    private val IMPLICIT_DENY_EVALUATION = Evaluation(IMPLICIT_DENY, null)

    override fun requiresPolicies(request: AuthzRequest): Boolean =
        !request.environment.blocked && !request.subject.root

    override fun evaluate(request: AuthzRequest, policies: PolicySet, trace: TraceLevel): Evaluation {
        if (trace == TraceLevel.OFF) {
            if (request.environment.blocked) {
                return BLACKLIST_EVALUATION
            }
            if (request.subject.root) {
                return ROOT_EVALUATION
            }
        }
        val evaluator = if (trace == TraceLevel.OFF) Evaluator(request) else TracingEvaluator(request, trace)
        return evaluator.evaluate(policies.layers)
    }

    internal fun untraced(decision: Decision): Evaluation =
        if (decision === IMPLICIT_DENY) IMPLICIT_DENY_EVALUATION else Evaluation(decision, null)
}
