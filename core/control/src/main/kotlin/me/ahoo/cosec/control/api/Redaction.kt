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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef

/**
 * What a caller may see of a decision and its trace: statements of policies defined in a document it may read (RFC 0007
 * §10.3, `CTL-51`). [PolicyHomes] says which document defines the policy a binding names, whichever document the
 * binding lives in. A statement reached through an attachment may come from the tenant or the platform, so it is shown
 * only when both are readable.
 */
internal class Redaction(private val readable: Set<DocumentKind>) {
    fun decision(decision: Decision, homes: PolicyHomes): DecisionView = when (decision) {
        is Decision.Permit -> view(ControlVocabulary.PERMIT, decision.by, homes)
        is Decision.Deny -> view(ControlVocabulary.DENY, decision.by, homes)
        is Decision.Indeterminate -> DecisionView(
            ControlVocabulary.INDETERMINATE,
            null,
            ControlVocabulary.cause(decision.cause),
            redacted = false
        )
    }

    fun trace(evaluation: Evaluation, homes: PolicyHomes): TraceView? {
        val trace = evaluation.trace ?: return null
        val visible = trace.candidates.filter { visible(it.ref, homes) }
        return TraceView(
            visible.map { TraceEntryView(
                    statement(it.ref),
                    ControlVocabulary.effect(it.effect),
                    ControlVocabulary.outcome(it.outcome)
                ) },
            trace.candidates.size - visible.size,
        )
    }

    private fun view(kind: String, by: StatementRef?, homes: PolicyHomes): DecisionView {
        val policy = by as? StatementRef.Policy
        return when {
            by == null -> DecisionView(kind, null, null, redacted = false)
            policy != null && visible(policy, homes) -> DecisionView(kind, statement(policy), null, redacted = false)
            else -> DecisionView(kind, null, null, redacted = true)
        }
    }

    private fun visible(ref: StatementRef.Policy, homes: PolicyHomes): Boolean = when (val via = ref.via) {
        is BindingRef.Bound -> homes.of(via.document, ref.policy)?.let { it in readable } == true
        is BindingRef.Attached -> DocumentKind.TENANT in readable && DocumentKind.PLATFORM in readable
        else -> DocumentKind.PLATFORM in readable
    }

    private fun statement(ref: StatementRef.Policy): StatementView {
        val (via, document) = when (val binding = ref.via) {
            is BindingRef.Bound -> binding.id.value to binding.document.name.lowercase()
            is BindingRef.Attached -> "attached:${binding.policy.value}" to null
            else -> binding.toString() to null
        }
        return StatementView(ref.policy.value, ref.index, ref.name, via, document)
    }
}
