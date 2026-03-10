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

import me.ahoo.cosec.api.Named

/**
 * Group of related permissions.
 *
 * Permission groups organize permissions into logical sets,
 * such as "users", "orders", "admin", etc.
 *
 * @see Permission
 * @see AppPermission
 */
interface PermissionGroup : Named {
    /** The name/identifier of this permission group */
    override val name: String

    /** Description of what this group contains */
    val description: String

    /** List of permissions in this group */
    val permissions: List<Permission>

    /**
     * Whether this group represents space-scoped resources.
     * Space-scoped resources are tied to a specific tenant/space.
     */
    val spaced: Boolean
}
