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

package me.ahoo.cosec.migrate.v7.api.permission

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.migrate.v7.api.policy.ConditionMatcher

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
internal interface AppPermission {
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
