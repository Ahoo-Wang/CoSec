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

import me.ahoo.cosec.api.policy.Statement

/** Type alias for permission identifier */
typealias PermissionId = String

/**
 * Permission metadata extending [Statement].
 *
 * A Permission represents a specific action or resource that can be granted.
 * It extends [Statement] to include the ability to verify permissions.
 *
 * Format: `appId.group.permission` (e.g., "order.read", "admin.users.delete")
 *
 * @see Statement
 * @see AppPermission
 * @see RolePermission
 */
interface Permission : Statement {
    /**
     * Unique permission identifier.
     *
     * Format: appId.group.permission
     */
    override val id: PermissionId

    /** Description of what this permission allows */
    val description: String
}
