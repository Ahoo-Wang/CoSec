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
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef

/**
 * The control of RFC 0002 §7.1: the §4.1 algorithm with no trace code at all, allocating what any implementation
 * must (the evaluation, its decision and reference, the visitor, the bitmap beyond 64 groups). [CoSecEngine] at
 * [TraceLevel.OFF] must allocate no more than this.
 */
object ControlEngine {
    private val IMPLICIT_DENY = Evaluation(Decision.Deny(null), null)

    fun evaluate(request: AuthzRequest, policies: PolicySet): Evaluation {
        if (request.environment.blocked) return Evaluation(CoSecEngine.BLACKLIST, null)
        if (request.subject.root) return Evaluation(CoSecEngine.ROOT, null)
        val visitor = ControlVisitor(request)
        var permit: Decision? = null
        val layers = policies.layers
        for (l in layers.indices) {
            val layer = layers[l]
            val result: Decision? = when (layer) {
                is LayerInput.Absent -> null
                is LayerInput.Unavailable -> Decision.Indeterminate(Cause.SourceUnavailable(layer.kind, layer.error))
                is LayerInput.Loaded -> layer.missing?.let { Decision.Indeterminate(it) } ?: visitor.evaluate(layer)
            }
            when (result) {
                null -> Unit
                is Decision.Permit -> if (permit == null) permit = result
                else -> return Evaluation(result, null)
            }
        }
        return if (permit == null) IMPLICIT_DENY else Evaluation(permit, null)
    }

    @Suppress("TooGenericExceptionCaught")
    private class ControlVisitor(private val request: AuthzRequest) : RuleVisitor {
        private var kind = LayerKind.GLOBAL
        private var groupId = ""
        private var pass = Effect.DENY
        private var result: Decision? = null

        fun evaluate(layer: LayerInput.Loaded): Decision? {
            kind = layer.kind
            result = null
            val groups = layer.groups
            var bits = 0L
            val words = if (groups.size > 64) LongArray(groups.size / 64 + 1) else null
            for (i in groups.indices) {
                val matched = try {
                    groups[i].guard.matches(request)
                } catch (e: Exception) {
                    return Decision.Indeterminate(Cause.MatcherFailed(kind, groups[i].id, null, e))
                }
                if (matched) {
                    if (words == null) bits = bits or (1L shl i) else words[i ushr 6] = words[i ushr 6] or (1L shl i)
                }
            }
            for (effect in DENY_FIRST) {
                pass = effect
                for (i in groups.indices) {
                    val word = words?.get(i ushr 6) ?: bits
                    if ((word ushr i) and 1L == 0L) continue
                    groupId = groups[i].id
                    if (groups[i].forEachRule(this)) return result
                }
            }
            return null
        }

        override fun visit(entry: RuleEntry, grantedVia: String?): Boolean {
            val rule = when (entry) {
                is Rule -> entry
                is UnresolvedGrant -> {
                    if (pass != Effect.DENY) return false
                    val ref = StatementRef.RolePermission(groupId, grantedVia!!, entry.permissionId, "")
                    result = Decision.Indeterminate(Cause.MissingPermissions(ref, entry.error))
                    return true
                }
            }
            if (rule.effect != pass) return false
            val match = try {
                rule.match(request)
            } catch (e: Exception) {
                result = Decision.Indeterminate(Cause.MatcherFailed(kind, groupId, ref(rule, grantedVia), e))
                return true
            }
            if (match != RuleMatch.MATCHED) return false
            val ref = ref(rule, grantedVia)
            result = if (pass == Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
            return true
        }

        private fun ref(rule: Rule, grantedVia: String?): StatementRef =
            if (kind == LayerKind.ROLE) {
                StatementRef.RolePermission(groupId, grantedVia!!, rule.id!!, rule.name)
            } else {
                StatementRef.PolicyStatement(kind, groupId, rule.index, rule.name)
            }
    }

    private val DENY_FIRST = arrayOf(Effect.DENY, Effect.ALLOW)
}
