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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ScopeResolverTest {
    private val acme = TenantId("acme")
    private val memberships = Memberships(
        Memberships.Source.CLAIMS,
        linkedMapOf(
            SpaceId.DEFAULT to linkedSetOf(RoleId("viewer"), RoleId("admin")),
            SpaceId("team-a") to linkedSetOf(RoleId("editor")),
            SpaceId("team-b") to emptySet(),
        ),
        emptyList(),
    )
    private val user = ScopeSubject(acme, authenticated = true, memberships)
    private val noHost = HostScope(null)

    private fun resolved(resolution: ScopeResolution): Scope {
        resolution.assert().isInstanceOf(ScopeResolution.Resolved::class.java)
        return (resolution as ScopeResolution.Resolved).scope
    }

    private fun rejectedFor(resolution: ScopeResolution): ScopeResolution.Rejected.Reason {
        resolution.assert().isInstanceOf(ScopeResolution.Rejected::class.java)
        return (resolution as ScopeResolution.Rejected).reason
    }

    @Test
    fun `without a declared space the default space and its roles apply`() {
        val scope = resolved(ScopeResolver.resolve(user, DeclaredScope("order", ""), noHost))
        scope.tenant.assert().isEqualTo(acme)
        scope.app.assert().isEqualTo(AppId("order"))
        scope.appSource.assert().isEqualTo(Scope.AppSource.DECLARED)
        scope.space.assert().isEqualTo(SpaceId.DEFAULT)
        scope.roles.assert().containsExactly(RoleId("viewer"), RoleId("admin"))
    }

    @Test
    fun `a declared space the principal has a role in applies with those roles only`() {
        val scope = resolved(ScopeResolver.resolve(user, DeclaredScope("order", "team-a"), noHost))
        scope.space.assert().isEqualTo(SpaceId("team-a"))
        scope.roles.assert().containsExactly(RoleId("editor"))
    }

    @Test
    fun `a declared space the principal has no role in is rejected`() {
        rejectedFor(ScopeResolver.resolve(user, DeclaredScope("order", "team-c"), noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        rejectedFor(ScopeResolver.resolve(user, DeclaredScope("order", "team-b"), noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
    }

    @Test
    fun `without any role the default space still applies`() {
        val nobody = ScopeSubject(acme, true, Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
        val scope = resolved(ScopeResolver.resolve(nobody, DeclaredScope("", ""), noHost))
        scope.app.assert().isEqualTo(AppId(""))
        scope.space.assert().isEqualTo(SpaceId.DEFAULT)
        scope.roles.assert().isEmpty()
    }

    /** Spaces match exactly: no trimming, no case folding, and the wildcard is never a member. */
    @Test
    fun `a near miss of a member space is rejected`() {
        for (space in listOf(" team-a", "team-a ", "TEAM-A", "*", "a@b", "team-a@team-a")) {
            rejectedFor(ScopeResolver.resolve(user, DeclaredScope("order", space), noHost))
                .assert().describedAs(space).isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        }
    }

    @Test
    fun `a declared app that cannot be an app id is rejected with or without a host app`() {
        val invalid = listOf("*", "a:b", ":", " ", "x".repeat(ScopeResolver.MAX_APP_ID_LENGTH + 1))
        for (host in listOf(noHost, HostScope(AppId("order")))) {
            for (app in invalid) {
                rejectedFor(ScopeResolver.resolve(user, DeclaredScope(app, ""), host))
                    .assert().describedAs(app).isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
            }
        }
        val longest = "x".repeat(ScopeResolver.MAX_APP_ID_LENGTH)
        resolved(ScopeResolver.resolve(user, DeclaredScope(longest, ""), noHost)).app.assert()
            .isEqualTo(AppId(longest))
    }

    @Test
    fun `a near miss of the host app is rejected`() {
        for (app in listOf(" order", "order ", "ORDER", "a@b")) {
            rejectedFor(ScopeResolver.resolve(user, DeclaredScope(app, ""), HostScope(AppId("order"))))
                .assert().describedAs(app).isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
        }
    }

    @Test
    fun `blank tenants and invalid host apps cannot be given`() {
        for (tenant in listOf("", " ")) {
            runCatching { ScopeSubject(TenantId(tenant), true, memberships) }.isFailure.assert().isTrue()
        }
        for (app in listOf("", " ", "a:b")) {
            runCatching { HostScope(AppId(app)) }.isFailure.assert().describedAs(app).isTrue()
        }
    }

    @Test
    fun `the host app applies when nothing or the same app is declared`() {
        val host = HostScope(AppId("order"))
        for (declared in listOf("", "order")) {
            val scope = resolved(ScopeResolver.resolve(user, DeclaredScope(declared, ""), host))
            scope.app.assert().isEqualTo(AppId("order"))
            scope.appSource.assert().isEqualTo(Scope.AppSource.HOST)
        }
    }

    @Test
    fun `a declared app other than the host app is rejected`() {
        rejectedFor(ScopeResolver.resolve(user, DeclaredScope("billing", ""), HostScope(AppId("order"))))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    }

    @Test
    fun `the app is checked before the space`() {
        rejectedFor(ScopeResolver.resolve(user, DeclaredScope("billing", "team-c"), HostScope(AppId("order"))))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    }

    /** 7.x builds every anonymous context in the default tenant; the resolver does not trust anything else. */
    @Test
    fun `an anonymous subject is in the default tenant and has no roles`() {
        val anonymous = ScopeSubject(acme, authenticated = false, memberships)
        val scope = resolved(ScopeResolver.resolve(anonymous, DeclaredScope("order", ""), noHost))
        scope.tenant.assert().isEqualTo(TenantId.DEFAULT)
        scope.roles.assert().isEmpty()
        rejectedFor(ScopeResolver.resolve(anonymous, DeclaredScope("order", "team-a"), noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
    }

    @Test
    fun `rejections are shared and carry their reason`() {
        val first = ScopeResolver.resolve(user, DeclaredScope("order", "team-c"), noHost)
        first.assert().isSameAs(ScopeResolver.resolve(user, DeclaredScope("order", "team-d"), noHost))
        ScopeResolution.Rejected.Reason.entries.assert()
            .containsExactly(
                ScopeResolution.Rejected.Reason.APP_MISMATCH,
                ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER
            )
    }
}
