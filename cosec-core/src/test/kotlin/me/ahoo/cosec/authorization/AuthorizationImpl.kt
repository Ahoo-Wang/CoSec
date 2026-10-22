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

import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.parity.AuthorizationFactory

/**
 * The two authorization implementations every decision test runs against until the legacy one is removed
 * (RFC 0002 §8.2): use it with `@ParameterizedClass @EnumSource(AuthorizationImpl::class)`.
 */
enum class AuthorizationImpl(private val delegate: AuthorizationFactory) {
    LEGACY(AuthorizationFactory.LEGACY),
    ENGINE(AuthorizationFactory.ENGINE);

    fun create(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ): DecisionAuthorization =
        delegate.create(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher)

    /** For [me.ahoo.cosec.shadow.ShadowAuthorization]'s candidate factory. */
    val factory:
        (PolicyRepository, AppRolePermissionRepository, BlacklistChecker, RootPrincipalMatcher) -> DecisionAuthorization
        get() = { policies, permissions, blacklist, root -> create(policies, permissions, blacklist, root) }
}
