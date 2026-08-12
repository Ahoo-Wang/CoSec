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
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PATH_VARIABLES_KEY
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.isRoot
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import reactor.core.publisher.Mono
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

    private data class VerifyCandidate(
        val effect: Effect,
        val verify: () -> VerifyResult,
        val onMatch: (VerifyResult) -> VerifyContext
    )

    private inline fun <T> evaluateDenyFirst(
        items: List<T>,
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
    ): List<VerifyCandidate> {
        return policies.flatMap { policy ->
            val policyMatches by lazy {
                policy.condition.match(request = request, securityContext = securityContext)
            }
            policy.statements.mapIndexed { index, statement ->
                VerifyCandidate(
                    effect = statement.effect,
                    verify = {
                        if (policyMatches) {
                            statement.verify(request, securityContext)
                        } else {
                            VerifyResult.IMPLICIT_DENY
                        }
                    },
                    onMatch = { result ->
                        log.debug {
                            "Verify [$request] [$securityContext] matched Policy[${policy.id}] Statement[$index][${statement.name}] - [$result]."
                        }
                        PolicyVerifyContext(
                            policy = policy,
                            statementIndex = index,
                            statement = statement,
                            result = result,
                        )
                    }
                )
            }
        }
    }

    private fun verifyAppRolePermission(
        appRolePermission: AppRolePermission,
        request: Request,
        context: SecurityContext
    ): List<VerifyCandidate> {
        val appPermissionMatches by lazy {
            appRolePermission.appPermission.condition.match(request, context)
        }

        return appRolePermission.rolePermissionIndexer.entries.flatMap { (roleId, permissions) ->
            permissions.map { permission ->
                VerifyCandidate(
                    effect = permission.effect,
                    verify = {
                        if (appPermissionMatches) {
                            permission.verify(request, context)
                        } else {
                            VerifyResult.IMPLICIT_DENY
                        }
                    },
                    onMatch = { result ->
                        log.debug {
                            "Verify [$request] [$context] matched Role[$roleId] Permission[${permission.id}][${permission.name}] - [$result]."
                        }
                        RoleVerifyContext(
                            roleId = roleId,
                            permission = permission,
                            result = result,
                        )
                    }
                )
            }
        }
    }

    private fun verifyCandidates(candidates: List<VerifyCandidate>): VerifyContext? =
        evaluateDenyFirst(
            items = candidates,
            effectExtractor = { it.effect },
            verifyItem = { it.verify() },
            onMatch = { candidate, result -> candidate.onMatch(result) },
        )

    private fun verifyRoot(context: SecurityContext): VerifyResult =
        if (context.principal.isRoot) {
            log.debug {
                "Verify [$context] matched Root - [Allow]."
            }
            VerifyResult.ALLOW
        } else {
            VerifyResult.IMPLICIT_DENY
        }

    private fun getPrincipalPolicies(context: SecurityContext): Mono<List<Policy>> {
        if (context.principal.policies.isEmpty()) {
            return emptyList<Policy>().toMono()
        }
        return policyRepository
            .getPolicies(context.principal.policies)
            .defaultIfEmpty(emptyList())
    }

    private fun getAppRolePermissions(
        request: Request,
        context: SecurityContext
    ): Mono<List<AppRolePermission>> {
        if (context.principal.roles.isEmpty()) {
            return emptyList<AppRolePermission>().toMono()
        }
        return appRolePermissionRepository
            .getAppRolePermission(request.appId, request.spaceId, context.principal.roles)
            .map { listOf(it) }
            .defaultIfEmpty(emptyList())
    }

    private fun verify(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> {
        val globalPolicies = policyRepository.getGlobalPolicy().defaultIfEmpty(emptyList())
        return Mono.zip(
            globalPolicies,
            getPrincipalPolicies(context),
            getAppRolePermissions(request, context),
        ).mapNotNull { sources ->
            val candidates = buildList {
                addAll(
                    verifyPolicies(
                        (sources.t1 + sources.t2).distinctBy { it.id },
                        request,
                        context,
                    )
                )
                sources.t3.forEach { appRolePermission ->
                    addAll(verifyAppRolePermission(appRolePermission, request, context))
                }
            }
            verifyCandidates(candidates)
        }.map {
            context.setVerifyContext(it)
            it.result.toAuthorizeResult()
        }.switchIfEmpty(
            Mono.defer {
                log.debug {
                    "Verify [$request] [$context] No policies matched - [Implicit Deny]."
                }
                AuthorizeResult.IMPLICIT_DENY.toMono()
            }
        )
    }

    override fun authorize(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> {
        context.setAttributeValue(PATH_VARIABLES_KEY, emptyMap<String, String>())
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
