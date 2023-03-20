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
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.isRoot
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

/**
 * Simple Authorization .
 *
 * @author ahoo wang
 */
class SimpleAuthorization(
    private val policyRepository: PolicyRepository,
    private val rolePermissionRepository: RolePermissionRepository
) : Authorization {
    companion object {
        private val log = LoggerFactory.getLogger(SimpleAuthorization::class.java)
    }

    private fun verifyPolicies(
        policies: List<Policy>,
        request: Request,
        context: SecurityContext
    ): VerifyContext? {
        policies.forEach { policy: Policy ->
            policy.statements.filter { statement: Statement ->
                statement.effect == Effect.DENY
            }.forEachIndexed { index, statement ->
                val verifyResult = statement.verify(request, context)
                if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "Verify [$request] [$context] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [Explicit Deny].",
                        )
                    }
                    return PolicyVerifyContext(
                        policy = policy,
                        statementIndex = index,
                        statement = statement,
                        result = verifyResult
                    )
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
                            "Verify [$request] [$context] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [Allow].",
                        )
                    }
                    return PolicyVerifyContext(
                        policy = policy,
                        statementIndex = index,
                        statement = statement,
                        result = verifyResult
                    )
                }
            }
        }
        /**
         * [VerifyResult.IMPLICIT_DENY]
         */
        return null
    }

    private fun verifyRolePermissions(
        rolePermissions: List<RolePermission>,
        request: Request,
        context: SecurityContext
    ): VerifyContext? {
        rolePermissions.forEach { rolePermission: RolePermission ->
            rolePermission.permissions.filter { permission: Permission ->
                permission.effect == Effect.DENY
            }.forEach { permission ->
                val verifyResult = permission.verify(request, context)
                if (verifyResult == VerifyResult.EXPLICIT_DENY) {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "Verify [$request] [$context] matched Role[${rolePermission.id}] Permission[${permission.id}][${permission.name}] - [Explicit Deny].",
                        )
                    }
                    return RoleVerifyContext(
                        roleId = rolePermission.id,
                        permission = permission,
                        result = verifyResult
                    )
                }
            }
        }

        rolePermissions.forEach { rolePermission: RolePermission ->
            rolePermission.permissions.filter { statement: Statement ->
                statement.effect == Effect.ALLOW
            }.forEach { permission ->
                val verifyResult = permission.verify(request, context)
                if (verifyResult == VerifyResult.ALLOW) {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "Verify [$request] [$context] matched Role[${rolePermission.id}] Permission[${permission.id}][${permission.name}] - [Allow].",
                        )
                    }
                    return RoleVerifyContext(
                        roleId = rolePermission.id,
                        permission = permission,
                        result = verifyResult
                    )
                }
            }
        }
        /**
         * [VerifyResult.IMPLICIT_DENY]
         */
        return null
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

    private fun verifyGlobalPolicies(request: Request, context: SecurityContext): Mono<VerifyContext> {
        return policyRepository.getGlobalPolicy()
            .mapNotNull { policies: List<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyPrincipalPolicies(request: Request, context: SecurityContext): Mono<VerifyContext> {
        if (context.principal.policies.isEmpty()) {
            return Mono.empty()
        }
        return policyRepository.getPolicies(context.principal.policies)
            .mapNotNull { policies: List<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyRolePolicies(request: Request, context: SecurityContext): Mono<VerifyContext> {
        if (context.principal.roles.isEmpty()) {
            return Mono.empty()
        }
        return rolePermissionRepository.getRolePermissions(request.appId, context.principal.roles)
            .mapNotNull {
                verifyRolePermissions(it, request, context)
            }
    }

    override fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult> {
        val verifyResult = verifyRoot(context)
        if (verifyResult == VerifyResult.ALLOW) {
            return AuthorizeResult.ALLOW.toMono()
        }

        return verifyGlobalPolicies(request, context)
            .switchIfEmpty {
                verifyPrincipalPolicies(request, context)
            }
            .switchIfEmpty {
                verifyRolePolicies(request, context)
            }
            .map {
                context.setVerifyContext(it)
                when (it.result) {
                    VerifyResult.ALLOW -> AuthorizeResult.ALLOW
                    VerifyResult.EXPLICIT_DENY -> AuthorizeResult.EXPLICIT_DENY
                    VerifyResult.IMPLICIT_DENY -> throw IllegalStateException("VerifyResult.IMPLICIT_DENY")
                }
            }.switchIfEmpty {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Verify [$request] [$context] No policies matched - [Implicit Deny].",
                    )
                }
                AuthorizeResult.IMPLICIT_DENY.toMono()
            }
    }
}
