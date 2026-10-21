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

package me.ahoo.cosec.cache

import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.blacklist.BlacklistChecker

/**
 * The two authorization implementations every decision test runs against until the legacy one is removed
 * (RFC 0002 §8.2). A copy of cosec-core's test fixture of the same name, removed with it.
 */
enum class AuthorizationImpl {
    LEGACY {
        override fun create(
            policyRepository: PolicyRepository,
            appRolePermissionRepository: AppRolePermissionRepository,
            blacklistChecker: BlacklistChecker,
            rootPrincipalMatcher: RootPrincipalMatcher
        ): DecisionAuthorization =
            SimpleAuthorization(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher)
    },
    ENGINE {
        override fun create(
            policyRepository: PolicyRepository,
            appRolePermissionRepository: AppRolePermissionRepository,
            blacklistChecker: BlacklistChecker,
            rootPrincipalMatcher: RootPrincipalMatcher
        ): DecisionAuthorization =
            EngineAuthorization(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher)
    };

    abstract fun create(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ): DecisionAuthorization

    /** For [me.ahoo.cosec.shadow.ShadowAuthorization]'s candidate factory. */
    val factory: (
        PolicyRepository,
        AppRolePermissionRepository,
        BlacklistChecker,
        RootPrincipalMatcher
    ) -> DecisionAuthorization
        get() = { policies, permissions, blacklist, root -> create(policies, permissions, blacklist, root) }
}
