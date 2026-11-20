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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.document.Catalog
import me.ahoo.cosec.document.CatalogGroup
import me.ahoo.cosec.document.CatalogPermission
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `ROL-12`: a membership is `role@space`, with no app in it, so a member whose roles are not issued per app holds a
 * role in every app whose document defines that id. A change to an app document that defines a system role another
 * app's head already defines carries a warning naming the role and the other apps; it is never refused.
 */
class SharedRoleIdTest {
    private val harness = ChangeHarness(options = ChangeOptions(shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes
    private val wms = SnapshotKey.App("wms")
    private val ops = SnapshotKey.App("ops")

    private fun catalog(app: String) = Catalog(
        listOf(CatalogGroup("all", permissions = listOf(CatalogPermission("read", actions = listOf("$app:read"))))),
    )

    private fun app(key: SnapshotKey.App, vararg roles: String): List<Operation> =
        listOf(Operation.PutCatalog(catalog(key.appId))) +
            roles.map { Operation.PutRole(RoleDefinition(RoleId(it), RolePermissions.All)) }

    private suspend fun proposed(key: SnapshotKey.App, vararg roles: String): ChangeRequest =
        changes.propose(PLATO, key, harness.revisions.head(key)?.revision ?: 0, app(key, *roles), "roles")

    private fun ChangeRequest.roleWarnings(): List<String> =
        findings.filter { it.severity == Severity.WARNING && it.path.startsWith("$.roles") }.map { it.message }

    private suspend fun published(key: SnapshotKey.App, vararg roles: String): ChangeRequest {
        val change = proposed(key, *roles)
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(PLATO, change.id, "test")
        return changes.publish(PLATO, change.id)
    }

    @Test
    fun `an app that defines a system role another app defines is warned, naming the role and the app`() =
        runBlocking<Unit> {
            published(wms, "clerk", "picker")
            val change = proposed(ops, "clerk", "auditor")
            change.errors.assert().isEmpty()
            change.roleWarnings().assert().containsExactly(
                "[clerk], a system role of this app, is also a system role of app wms: a member who holds it once " +
                    "holds it in both apps unless their roles are issued per app; prefix the id with the app " +
                    "(ops.clerk) when that is not meant",
            )
        }

    @Test
    fun `an app whose role ids no other app defines is not warned`() = runBlocking<Unit> {
        published(wms, "picker")
        proposed(ops, "clerk").roleWarnings().assert().isEmpty()
    }

    /** A warning, never a refusal: the change publishes, and the published change keeps the warning of its check. */
    @Test
    fun `an app that defines a role another app defines still publishes, keeping the warning`() = runBlocking<Unit> {
        published(wms, "clerk")
        val change = published(ops, "clerk")
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        change.roleWarnings().single().assert().startsWith(
            "[clerk], a system role of this app, is also a system role of app wms"
        )
    }

    /** With more than one other app, the warning names them all and counts the apps, not "both". */
    @Test
    fun `with two other apps the warning names both and counts three apps`() = runBlocking<Unit> {
        published(wms, "clerk")
        published(SnapshotKey.App("crm"), "clerk")
        proposed(ops, "clerk").roleWarnings().single().assert()
            .contains("is also a system role of app crm, wms").contains("holds it in all 3 apps")
    }
}
