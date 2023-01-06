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
package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.isRoot
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Simple Authorization .
 *
 * @author ahoo wang
 */
class SimpleAuthorization(private val policyRepository: PolicyRepository) : Authorization {
    companion object {
        private val log = LoggerFactory.getLogger(SimpleAuthorization::class.java)
    }

    private fun verifyPolicies(policies: Set<Policy>, request: Request, context: SecurityContext): VerifyResult {
        policies.forEach { policy: Policy ->
            policy.statements.filter { statement: Statement ->
                statement.effect == Effect.DENY
            }.forEachIndexed { index, statement ->
                val verifyResult = statement.verify(request, context)
                if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "Verify [$request] [$context] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [Explicit Deny]."
                        )
                    }
                    return VerifyResult.EXPLICIT_DENY
                }
            }
        }

        policies.forEach { policy: Policy ->
            policy.statements.filter { statement: Statement ->
                statement.effect == Effect.ALLOW
            }.forEachIndexed { index, statement ->
                val verifyResult = statement.verify(request, context)
                if (verifyResult == VerifyResult.ALLOW) {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "Verify [$request] [$context] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [Allow]."
                        )
                    }
                    return VerifyResult.ALLOW
                }
            }
        }

        return VerifyResult.IMPLICIT_DENY
    }

    private fun verifyRoot(context: SecurityContext): VerifyResult {
        return if (context.principal.isRoot()) {
            if (log.isDebugEnabled) {
                log.debug("Verify [$context] matched Root - [Allow].")
            }
            VerifyResult.ALLOW
        } else {
            VerifyResult.IMPLICIT_DENY
        }
    }

    private fun verifyGlobalPolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        return policyRepository.getGlobalPolicy()
            .defaultIfEmpty(emptySet())
            .map { policies: Set<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyPrincipalPolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        if (context.principal.policies.isEmpty()) {
            return VerifyResult.IMPLICIT_DENY.toMono()
        }
        return policyRepository.getPolicies(context.principal.policies)
            .defaultIfEmpty(emptySet())
            .map { policies: Set<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyRolePolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        if (context.principal.roles.isEmpty()) {
            return VerifyResult.IMPLICIT_DENY.toMono()
        }
        return policyRepository.getRolePolicy(context.principal.roles)
            .defaultIfEmpty(emptySet())
            .map { policies: Set<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    override fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult> {
        val verifyResult = verifyRoot(context)
        if (verifyResult == VerifyResult.ALLOW) {
            return AuthorizeResult.ALLOW.toMono()
        }

        return verifyGlobalPolicies(request, context)
            .flatMap { globalVerifyResult: VerifyResult ->
                if (globalVerifyResult == VerifyResult.IMPLICIT_DENY) {
                    return@flatMap verifyPrincipalPolicies(request, context)
                }
                globalVerifyResult.toMono()
            }
            .flatMap { principalVerifyResult: VerifyResult ->
                when (principalVerifyResult) {
                    VerifyResult.ALLOW -> AuthorizeResult.ALLOW.toMono()
                    VerifyResult.EXPLICIT_DENY -> AuthorizeResult.EXPLICIT_DENY.toMono()
                    VerifyResult.IMPLICIT_DENY -> {
                        verifyRolePolicies(request, context)
                            .map { roleVerifyResult: VerifyResult ->
                                when (roleVerifyResult) {
                                    VerifyResult.ALLOW -> AuthorizeResult.ALLOW
                                    VerifyResult.EXPLICIT_DENY -> AuthorizeResult.EXPLICIT_DENY
                                    VerifyResult.IMPLICIT_DENY -> {
                                        if (log.isDebugEnabled) {
                                            log.debug(
                                                "Verify [$request] [$context] No policies matched - [Implicit Deny]."
                                            )
                                        }
                                        AuthorizeResult.IMPLICIT_DENY
                                    }
                                }
                            }
                    }
                }
            }
    }
}
