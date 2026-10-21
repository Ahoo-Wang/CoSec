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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * RFC 0001, D5: root used to be decided by principal id alone (`cosec`, or `-Dcosec.root`) and skipped the
 * blacklist.
 *
 * Root is now an explicit grant (`cosec.authorization.root-enabled` defaults to `false`). When enabled, only the
 * platform tenant's principal with the root id is root, and the blacklist still applies to it. The tests pass
 * [RootPrincipalMatcher.DEFAULT] explicitly: it is what `root-enabled=true` installs.
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class RootPrincipalIdentityTest(private val impl: AuthorizationImpl) {
    private val blockEverything = object : BlacklistChecker {
        override fun check(request: Request, context: SecurityContext): Mono<Boolean> = false.toMono()
    }

    private fun authorization(blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp) = impl.create(
        policyRepository = mockk {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        },
        appRolePermissionRepository = mockk(),
        blacklistChecker = blacklistChecker,
        rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT,
    )

    private val platformRoot = SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM)

    private val request = EvaluateRequest(path = "/admin/users", method = "DELETE")

    @Test
    fun rootIdIsTheDefault() {
        // Precondition for the tests below.
        CoSecPrincipal.ROOT_ID.assert().isEqualTo("cosec")
    }

    @Test
    fun platformRootIsRoot() {
        // Precondition for the tests below: without the blacklist, this principal is allowed as root.
        val decision = checkNotNull(authorization().decide(request, SimpleSecurityContext(platformRoot)).block())
        decision.assert().isEqualTo(AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext))
    }

    @Test
    fun blacklistAppliesToRoot() {
        val context = SimpleSecurityContext(platformRoot)
        val decision = checkNotNull(authorization(blockEverything).decide(request, context).block())
        decision.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        decision.verifyContext.assert().isEqualTo(BlacklistVerifyContext)
    }

    @Test
    fun principalNamedRootInAUserTenantIsNotPlatformRoot() {
        // An identity provider for tenant "acme" lets users pick their own id, and one picks "cosec".
        val principal = SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant("acme"))
        val context = SimpleSecurityContext(principal)
        val decision = checkNotNull(authorization().decide(request, context).block())
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun principalNamedRootWithoutATenantIsNotPlatformRoot() {
        val context = SimpleSecurityContext(SimplePrincipal(CoSecPrincipal.ROOT_ID))
        val decision = checkNotNull(authorization().decide(request, context).block())
        decision.result.authorized.assert().isFalse()
    }
}
