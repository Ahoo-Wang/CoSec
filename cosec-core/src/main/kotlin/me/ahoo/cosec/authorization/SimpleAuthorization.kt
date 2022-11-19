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

import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.request.Request
import me.ahoo.cosec.policy.Effect
import me.ahoo.cosec.policy.Policy
import me.ahoo.cosec.policy.Statement
import me.ahoo.cosec.policy.VerifyResult
import me.ahoo.cosec.principal.CoSecPrincipal.Companion.isRoot
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Simple Authorization .
 *
 * @author ahoo wang
 */
class SimpleAuthorization(private val permissionRepository: PermissionRepository) : Authorization {

    private fun verifyPolicies(policies: Set<Policy>, request: Request, context: SecurityContext): VerifyResult {
        policies.forEach { policy: Policy ->
            policy.statements.filter { statement: Statement ->
                statement.effect == Effect.DENY
            }.forEach { statement: Statement ->
                val verifyResult = statement.verify(request, context)
                if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                    return VerifyResult.EXPLICIT_DENY
                }
            }
        }

        policies.forEach { policy: Policy ->
            policy.statements.filter { statement: Statement ->
                statement.effect == Effect.ALLOW
            }.forEach { statement: Statement ->
                val verifyResult = statement.verify(request, context)
                if (verifyResult == VerifyResult.ALLOW) {
                    return VerifyResult.ALLOW
                }
            }
        }

        return VerifyResult.IMPLICIT_DENY
    }

    private fun verifyRoot(context: SecurityContext): VerifyResult {
        return if (context.principal.isRoot()) {
            VerifyResult.ALLOW
        } else {
            VerifyResult.IMPLICIT_DENY
        }
    }

    private fun verifyGlobalPolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        return permissionRepository.getGlobalPolicy()
            .defaultIfEmpty(emptySet())
            .map { policies: Set<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyPrincipalPolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        if (context.principal.policies.isEmpty()) {
            return VerifyResult.IMPLICIT_DENY.toMono()
        }
        return permissionRepository.getPolicies(context.principal.policies)
            .defaultIfEmpty(emptySet())
            .map { policies: Set<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyRolePolicies(request: Request, context: SecurityContext): Mono<VerifyResult> {
        if (context.principal.roles.isEmpty()) {
            return VerifyResult.IMPLICIT_DENY.toMono()
        }
        return permissionRepository.getRolePolicy(context.principal.roles)
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

        if (context.principal.authenticated()) {
            if (request.tenantId != context.tenant.tenantId) {
                return IllegalTenantContextException(request, context).toMono()
            }
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
                                    VerifyResult.IMPLICIT_DENY -> AuthorizeResult.IMPLICIT_DENY
                                }
                            }
                    }
                }
            }
    }
}
