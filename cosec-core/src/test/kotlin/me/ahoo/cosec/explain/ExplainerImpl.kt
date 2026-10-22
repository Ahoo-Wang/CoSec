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

package me.ahoo.cosec.explain

import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.limiter.RateLimitRuleProvider

/**
 * The two ways of explaining a decision until the narration is removed (RFC 0002 §5.2, PR-6): use it with
 * `@ParameterizedClass @EnumSource(ExplainerImpl::class)`. Assertions only one of them meets are marked with the
 * RFC 0002 entry that explains the difference.
 */
enum class ExplainerImpl(val fromTrace: Boolean) {
    /** The narration production uses. */
    NARRATION(false),

    /** The engine's trace of the one evaluation that decides. */
    TRACE(true);

    fun create(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
        rateLimitRuleProvider: RateLimitRuleProvider? = null,
        securityContextParser: SecurityContextParser? = null
    ): AuthorizationExplainer = if (fromTrace) {
        AuthorizationExplainer.fromTrace(
            policyRepository,
            appRolePermissionRepository,
            blacklistChecker,
            rootPrincipalMatcher,
            rateLimitRuleProvider,
            securityContextParser,
        )
    } else {
        AuthorizationExplainer(
            policyRepository,
            appRolePermissionRepository,
            blacklistChecker,
            rootPrincipalMatcher,
            rateLimitRuleProvider,
            securityContextParser,
        )
    }
}
