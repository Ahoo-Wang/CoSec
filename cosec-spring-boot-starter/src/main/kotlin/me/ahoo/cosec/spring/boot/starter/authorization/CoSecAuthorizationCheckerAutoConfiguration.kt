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
package me.ahoo.cosec.spring.boot.starter.authorization

import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationChecker
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Provides the [AuthorizationChecker] for checks in code.
 *
 * It decides with a plain [SimpleAuthorization] built from the same components as the request authorization, not
 * with the decorated `Authorization` bean, so checks are neither audited nor rate limited.
 */
@AutoConfiguration(after = [CoSecAuthorizationAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnAuthorizationEnabled
class CoSecAuthorizationCheckerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authorizationChecker(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker,
        rootPrincipalMatcher: RootPrincipalMatcher
    ): AuthorizationChecker {
        return AuthorizationChecker(
            SimpleAuthorization(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher),
        )
    }
}
