package me.ahoo.cosec.api.permission

import me.ahoo.cosec.api.policy.ConditionMatcher

typealias AppId = String

/**
 * App permissions metadata.
 */
interface AppPermission {
    val id: AppId
    val condition: ConditionMatcher
    val groups: List<PermissionGroup>

    /**
     * PermissionId -> Permission
     */
    val permissionIndexer: Map<String, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
