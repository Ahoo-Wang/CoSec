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

package me.ahoo.cosec.api.permission

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class AppRolePermissionTest {

    private fun rolePermissionOf(id: RoleId, vararg permissions: PermissionId): RolePermission =
        object : RolePermission {
            override val id: RoleId = id
            override val permissions: Set<PermissionId> = permissions.toSet()
        }

    @Test
    fun wildcardRoleKeepsOtherRolesInIndex() {
        val readPermission = mockk<Permission>()
        val writePermission = mockk<Permission>()
        val appPermission = mockk<AppPermission> {
            every { permissionIndexer } returns mapOf(
                "permission:read" to readPermission,
                "permission:write" to writePermission,
            )
        }
        val appRolePermission = object : AppRolePermission {
            override val appPermission: AppPermission = appPermission
            override val rolePermissions: List<RolePermission> = listOf(
                rolePermissionOf("admin", AppRolePermission.ALL_PERMISSION_ID),
                rolePermissionOf("writer", "permission:write"),
            )
        }

        val indexer = appRolePermission.rolePermissionIndexer

        indexer.assert().containsKey("admin")
        indexer.assert().containsKey("writer")
        indexer.assert().hasSize(2)
        indexer.getValue("admin").assert().containsExactlyInAnyOrder(readPermission, writePermission)
        indexer.getValue("writer").assert().containsExactly(writePermission)
    }

    @Test
    fun rolesWithoutWildcardKeepOwnPermissions() {
        val readPermission = mockk<Permission>()
        val writePermission = mockk<Permission>()
        val appPermission = mockk<AppPermission> {
            every { permissionIndexer } returns mapOf(
                "permission:read" to readPermission,
                "permission:write" to writePermission,
            )
        }
        val appRolePermission = object : AppRolePermission {
            override val appPermission: AppPermission = appPermission
            override val rolePermissions: List<RolePermission> = listOf(
                rolePermissionOf("reader", "permission:read"),
                rolePermissionOf("writer", "permission:write", "permission:unknown"),
            )
        }

        val indexer = appRolePermission.rolePermissionIndexer

        indexer.getValue("reader").assert().containsExactly(readPermission)
        indexer.getValue("writer").assert().containsExactly(writePermission)
    }
}
