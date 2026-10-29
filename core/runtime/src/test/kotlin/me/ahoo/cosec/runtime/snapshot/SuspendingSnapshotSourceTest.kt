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

package me.ahoo.cosec.runtime.snapshot

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class SuspendingSnapshotSourceTest {
    private val tenant = TenantId("tenant-a")

    private fun scope(app: String, vararg roles: String): Scope {
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.map { RoleId(it) }.toSet()),
            emptyList(),
        )
        val resolution = ScopeResolver.resolve(
            ScopeSubject(tenant, true, memberships),
            DeclaredScope(app, ""),
            HostScope(null)
        )
        return (resolution as ScopeResolution.Resolved).scope
    }

    /** RFC 0004 §7.4: which documents a request needs. */
    @Test
    fun viewKeys() {
        ViewKey.of(scope("shop", "admin"), blocked = true).assert().isSameAs(ViewKey.BLOCKED)
        ViewKey.BLOCKED.tenant.assert().isNull()

        val withApp = ViewKey.of(scope("shop", "admin"), blocked = false)
        withApp.tenant.assert().isEqualTo(tenant)
        withApp.app!!.value.assert().isEqualTo("shop")

        ViewKey.of(scope("shop"), blocked = false).app.assert().isNull()
        ViewKey.of(scope("", "admin"), blocked = false).app.assert().isNull()

        withApp.assert().isEqualTo(ViewKey.of(scope("shop", "admin"), blocked = false))
        withApp.hashCode().assert().isEqualTo(ViewKey.of(scope("shop", "admin"), blocked = false).hashCode())
        withApp.assert().isNotEqualTo(ViewKey.BLOCKED)
        withApp.toString().assert().isEqualTo("ViewKey(tenant=tenant-a, app=shop)")
    }

    @Test
    fun installedViewIsNotLoaded() = runTest {
        val installed = PipelineFixtures.InstalledViews()
        SuspendingSnapshotSource(installed).view(scope("shop", "admin"), blocked = false).app.assert().isNotNull()
        installed.loads.assert().isEmpty()
    }

    @Test
    fun missingViewIsLoaded() = runTest {
        val loaded = PipelineFixtures.viewFor(ViewKey.BLOCKED)
        val source =
            SuspendingSnapshotSource(PipelineFixtures.LoadingViews { CompletableFuture.completedFuture(loaded) })
        source.view(scope("shop"), blocked = true).assert().isSameAs(loaded)
    }
}
