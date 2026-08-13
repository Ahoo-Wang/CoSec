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
