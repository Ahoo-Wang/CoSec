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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.OnMissing
import me.ahoo.cosec.engine.OnMissingConfirmation
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect

/**
 * The control plane's side of `nomatch` DENYs (RFC 0007 §9.5, N6): it alone writes `onMissingConfirmed`, and only for a
 * statement the change's author confirmed, or one the base already had confirmed with the same content. Any other
 * confirmation in a candidate is dropped, so an author cannot copy one; a DENY with `nomatch` left without one is a
 * 428 (and would not compile).
 */
internal object NomatchConfirmations {
    fun apply(
        candidate: PolicyDocument,
        base: PolicyDocument?,
        confirmations: List<Confirmation>,
        author: Actor,
        change: ChangeId,
    ): Pair<PolicyDocument, List<Finding>> {
        val findings = ArrayList<Finding>()
        val before = base?.policies.orEmpty().associateBy { it.id }
        val policies = candidate.policies.mapIndexed { p, policy ->
            val statements = policy.statements.mapIndexed { s, statement ->
                val bare = StatementDigest.unconfirmed(statement)
                if (statement.effect != Effect.DENY || statement.onMissing != OnMissing.NOMATCH) {
                    return@mapIndexed bare
                }
                val digest = StatementDigest.of(statement, policy.condition)
                val previous = before[policy.id]
                val kept = previous?.statements?.getOrNull(s)
                    ?.takeIf { it.onMissingConfirmed != null && StatementDigest.of(it, previous.condition) == digest }
                    ?.onMissingConfirmed
                val confirmed = confirmations.any { it.policy == policy.id && it.statement == s && it.digest == digest }
                when {
                    kept != null -> confirmedAs(bare, kept)
                    confirmed -> confirmedAs(
                        bare,
                        OnMissingConfirmation(author.tenant.value, author.principal.value, change.value)
                    )
                    else -> {
                        findings += Finding(
                            "V5",
                            "$.policies[$p].statements[$s]",
                            "a DENY with onMissing: nomatch needs a deny-nomatch confirmation of digest $digest",
                            PRECONDITION_REQUIRED,
                        )
                        bare
                    }
                }
            }
            PolicyDefinition(policy.id, policy.attachable, policy.condition, statements, boundary = policy.boundary)
        }
        return candidate.copy(policies = policies) to findings
    }

    private fun confirmedAs(statement: StatementSource, confirmation: OnMissingConfirmation) = StatementSource(
        statement.name,
        statement.effect,
        statement.actions,
        statement.resources,
        statement.condition,
        statement.onMissing,
        confirmation,
    )

    private const val PRECONDITION_REQUIRED = 428
}
