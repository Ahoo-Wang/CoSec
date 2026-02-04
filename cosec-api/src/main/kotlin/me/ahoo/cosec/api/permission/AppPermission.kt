package me.ahoo.cosec.api.permission

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.policy.ConditionMatcher

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
    val permissionIndexer: Map<PermissionId, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
