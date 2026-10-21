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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.BlacklistVerifyContext
import me.ahoo.cosec.authorization.NoMatchVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifySource
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef

/**
 * The engine's decision as the [AuthorizationDecision] 7.0 returns (RFC 0002 §3.5). An indeterminate decision is not
 * mapped: the caller rethrows its cause's original exception, so the status codes stay as they are.
 */
internal object Decisions {
    val ROOT = AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext)
    val BLACKLIST = AuthorizationDecision(AuthorizeResult.EXPLICIT_DENY, BlacklistVerifyContext)
    val IMPLICIT_DENY = AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY, NoMatchVerifyContext)

    /**
     * Maps a permit or deny. [facts] is null only for a request decided before its policies were read (blacklist,
     * root).
     */
    fun of(decision: Decision, policies: PolicySet, facts: LegacyFacts?): AuthorizationDecision {
        val ref: StatementRef
        val result: VerifyResult
        when (decision) {
            is Decision.Permit -> {
                ref = decision.by
                result = VerifyResult.ALLOW
            }

            is Decision.Deny -> {
                ref = decision.by ?: return IMPLICIT_DENY
                result = VerifyResult.EXPLICIT_DENY
            }

            is Decision.Indeterminate -> throw IllegalArgumentException("An indeterminate decision has no result.")
        }
        return when (ref) {
            StatementRef.Blacklist -> expect(result == VerifyResult.EXPLICIT_DENY, decision, BLACKLIST)
            StatementRef.Root -> expect(result == VerifyResult.ALLOW, decision, ROOT)
            is StatementRef.PolicyStatement -> decided(policyContext(ref, result, policies, checkNotNull(facts)))
            is StatementRef.RolePermission -> decided(roleContext(ref, result, checkNotNull(facts)))
        }
    }

    private fun expect(valid: Boolean, decision: Decision, mapped: AuthorizationDecision): AuthorizationDecision {
        check(valid) { "The engine returned $decision." }
        return mapped
    }

    private fun decided(verifyContext: VerifyContext) =
        AuthorizationDecision(verifyContext.result.toAuthorizeResult(), verifyContext)

    /**
     * The policy and statement objects the decision refers to. The decided rule is the one the evaluation recorded
     * whose policy is in the decided layer; matching by identity keeps two policies that share an id apart.
     */
    private fun policyContext(
        ref: StatementRef.PolicyStatement,
        result: VerifyResult,
        policies: PolicySet,
        facts: LegacyFacts,
    ): PolicyVerifyContext {
        val layer = policies.layers.firstOrNull { it.kind == ref.layer } as? LayerInput.Loaded
        val groups = layer?.groups.orEmpty()
        val rule = facts.firstMatched { rule ->
            val policy = rule.policy
            policy != null && rule.index == ref.index && rule.name == ref.name && policy.id == ref.policyId &&
                groups.any { it is PolicyGroup && it.policy === policy }
        }
        checkNotNull(rule) { "The engine decided by $ref, which the evaluation did not match." }
        return PolicyVerifyContext(
            source = if (ref.layer == LayerKind.GLOBAL) PolicyVerifySource.GLOBAL else PolicyVerifySource.PRINCIPAL,
            policy = rule.policy!!,
            statementIndex = rule.index,
            statement = rule.statement,
            result = result,
        )
    }

    private fun roleContext(ref: StatementRef.RolePermission, result: VerifyResult, facts: LegacyFacts): RoleVerifyContext {
        val rule = facts.firstMatched { it.policy == null && it.id == ref.permissionId && it.name == ref.name }
        checkNotNull(rule) { "The engine decided by $ref, which the evaluation did not match." }
        return RoleVerifyContext(roleId = ref.roleId, permission = rule.statement as Permission, result = result)
    }
}
