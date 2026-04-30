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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.isRoot
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

/**
 * Simple Authorization implementation.
 *
 * This class provides the core authorization logic by evaluating:
 * 1. Root user bypass (root users always get ALLOW)
 * 2. Blacklist checks
 * 3. Global policies
 * 4. Principal-specific policies
 * 5. Role-based permissions
 *
 * @param policyRepository Repository for accessing policies
 * @param appRolePermissionRepository Repository for accessing role permissions
 * @param blacklistChecker Optional blacklist checker for blocking requests
 */
class SimpleAuthorization(
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
    private val blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp
) : Authorization {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private data class PolicyStatementEntry(val policy: Policy, val index: Int, val statement: Statement)

    private data class RolePermissionEntry(val roleId: RoleId, val permission: Permission)

    private inline fun <T> evaluateDenyFirst(
        items: Sequence<T>,
        crossinline effectExtractor: (T) -> Effect,
        crossinline verifyItem: (T) -> VerifyResult,
        crossinline onMatch: (T, VerifyResult) -> VerifyContext
    ): VerifyContext? {
        items.filter { effectExtractor(it) == Effect.DENY }.forEach { item ->
            val result = verifyItem(item)
            if (result == VerifyResult.EXPLICIT_DENY) {
                return onMatch(item, result)
            }
        }
        items.filter { effectExtractor(it) == Effect.ALLOW }.forEach { item ->
            val result = verifyItem(item)
            if (result == VerifyResult.ALLOW) {
                return onMatch(item, result)
            }
        }
        return null
    }

    private fun verifyPolicies(
        policies: List<Policy>,
        request: Request,
        securityContext: SecurityContext
    ): VerifyContext? {
        val matchedPolicies = policies.asSequence().filter { policy ->
            policy.condition.match(request = request, securityContext = securityContext)
        }

        val allStatements = matchedPolicies.flatMap { policy ->
            policy.statements.asSequence().mapIndexed { index, statement ->
                PolicyStatementEntry(policy, index, statement)
            }
        }

        return evaluateDenyFirst(
            items = allStatements,
            effectExtractor = { it.statement.effect },
            verifyItem = { it.statement.verify(request, securityContext) },
            onMatch = { entry, result ->
                log.debug {
                    "Verify [$request] [$securityContext] matched Policy[${entry.policy.id}] Statement[${entry.index}][${entry.statement.name}] - [$result]."
                }
                PolicyVerifyContext(
                    policy = entry.policy,
                    statementIndex = entry.index,
                    statement = entry.statement,
                    result = result,
                )
            }
        )
    }

    private fun verifyAppRolePermission(
        appRolePermission: AppRolePermission,
        request: Request,
        context: SecurityContext
    ): VerifyContext? {
        if (!appRolePermission.appPermission.condition.match(request, context)) {
            return null
        }

        val allPermissions =
            appRolePermission.rolePermissionIndexer.entries.asSequence().flatMap { (roleId, permissions) ->
                permissions.asSequence().map { permission -> RolePermissionEntry(roleId, permission) }
            }

        return evaluateDenyFirst(
            items = allPermissions,
            effectExtractor = { it.permission.effect },
            verifyItem = { it.permission.verify(request, context) },
            onMatch = { entry, result ->
                log.debug {
                    "Verify [$request] [$context] matched Role[${entry.roleId}] Permission[${entry.permission.id}][${entry.permission.name}] - [$result]."
                }
                RoleVerifyContext(
                    roleId = entry.roleId,
                    permission = entry.permission,
                    result = result,
                )
            }
        )
    }

    private fun verifyRoot(context: SecurityContext): VerifyResult =
        if (context.principal.isRoot) {
            log.debug {
                "Verify [$context] matched Root - [Allow]."
            }
            VerifyResult.ALLOW
        } else {
            VerifyResult.IMPLICIT_DENY
        }

    private fun verifyGlobalPolicies(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> =
        policyRepository
            .getGlobalPolicy()
            .mapNotNull { policies: List<Policy> ->
                verifyPolicies(policies, request, context)
            }

    private fun verifyPrincipalPolicies(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> {
        if (context.principal.policies.isEmpty()) {
            return Mono.empty()
        }
        return policyRepository
            .getPolicies(context.principal.policies)
            .mapNotNull { policies: List<Policy> ->
                verifyPolicies(policies, request, context)
            }
    }

    private fun verifyAppRolePermission(
        request: Request,
        context: SecurityContext
    ): Mono<VerifyContext> {
        if (context.principal.roles.isEmpty()) {
            return Mono.empty()
        }
        return appRolePermissionRepository
            .getAppRolePermission(request.appId, request.spaceId, context.principal.roles)
            .mapNotNull {
                verifyAppRolePermission(it, request, context)
            }
    }

    private fun verify(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> =
        verifyGlobalPolicies(request, context)
            .switchIfEmpty {
                verifyPrincipalPolicies(request, context)
            }.switchIfEmpty {
                verifyAppRolePermission(request, context)
            }.map {
                context.setVerifyContext(it)
                it.result.toAuthorizeResult()
            }.switchIfEmpty {
                log.debug {
                    "Verify [$request] [$context] No policies matched - [Implicit Deny]."
                }
                AuthorizeResult.IMPLICIT_DENY.toMono()
            }

    override fun authorize(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> {
        val verifyResult = verifyRoot(context)
        if (verifyResult == VerifyResult.ALLOW) {
            return AuthorizeResult.ALLOW.toMono()
        }
        return blacklistChecker
            .check(request, context)
            .flatMap { allowed ->
                if (!allowed) {
                    log.debug {
                        "Request [$request] is blocked by the blacklist."
                    }
                    return@flatMap AuthorizeResult.EXPLICIT_DENY.toMono()
                }
                verify(request, context)
            }
    }
}
