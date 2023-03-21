package me.ahoo.cosec.api.permission

import me.ahoo.cosec.api.policy.ConditionMatcher

/**
 * App permissions metadata.
 */
interface AppPermission {
    val id: String
    val condition: ConditionMatcher
    val groups: List<PermissionGroup>

    /**
     * PermissionId -> Permission
     */
    val permissionIndexer: Map<String, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
