package me.ahoo.cosec.api.permission

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.policy.ConditionMatcher

/**
 * Application permissions metadata.
 *
 * An AppPermission represents all permissions for a specific application.
 * It contains:
 * - The application ID
 * - A condition matcher to determine if these permissions apply
 * - Groups of related permissions
 *
 * @see Permission
 * @see PermissionGroup
 * @see AppRolePermission
 */
interface AppPermission {
    /** The application ID this permission belongs to */
    val id: AppId

    /**
     * Condition that must be met for these permissions to apply.
     * If the condition doesn't match, no permissions from this app are granted.
     */
    val condition: ConditionMatcher

    /** Groups of related permissions within this application */
    val groups: List<PermissionGroup>

    /**
     * Index of all permissions in this application by ID.
     *
     * @return Map of PermissionId to Permission
     */
    val permissionIndexer: Map<PermissionId, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
