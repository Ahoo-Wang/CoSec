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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/** The documented extension point for switching tenants (`wiki/integration/authentication.md`). */
class AbstractSwitchTenantAuthenticationTest {
    /** Lets a principal into the tenants it may enter, and nowhere else. */
    private class Switching(private val allowed: Set<String>) : AbstractSwitchTenantAuthentication() {
        override fun switchTenant(
            targetTenantId: String,
            previousPrincipal: CoSecPrincipal
        ): Mono<out TenantPrincipal> {
            if (targetTenantId !in allowed) {
                return Mono.error(
                    IllegalArgumentException("[${previousPrincipal.id}] may not enter [$targetTenantId].")
                )
            }
            return Mono.just(SimpleTenantPrincipal(previousPrincipal, SimpleTenant(targetTenantId)))
        }
    }

    private class Credentials(
        override val targetTenantId: String,
        override val principal: CoSecPrincipal,
    ) : SwitchTenantCredentials

    private val alice = SimplePrincipal("alice")

    @Test
    fun `it supports switch-tenant credentials`() {
        Switching(emptySet()).supportCredentials.assert().isEqualTo(SwitchTenantCredentials::class.java)
    }

    @Test
    fun `the principal is switched into the target tenant`() {
        Switching(setOf("acme")).authenticate(Credentials("acme", alice))
            .test()
            .consumeNextWith {
                it.tenant.tenantId.assert().isEqualTo("acme")
                it.id.assert().isEqualTo("alice")
            }
            .verifyComplete()
    }

    @Test
    fun `a switch the implementation refuses is an error`() {
        Switching(setOf("acme")).authenticate(Credentials("globex", alice))
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }
}
