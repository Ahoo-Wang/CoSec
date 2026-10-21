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

package me.ahoo.cosec.permission

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.authorization.engine.AppPermissionRules
import me.ahoo.cosec.policy.BlockingAware
import me.ahoo.cosec.policy.PathVariableScope
import me.ahoo.cosec.policy.computeRequiresBlocking
import me.ahoo.cosec.policy.condition.AllConditionMatcher

data class AppPermissionData(
    override val id: String,
    override val condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
    override val groups: List<PermissionGroup> = listOf()
) : AppPermission, BlockingAware {
    override val requiresBlocking: Boolean =
        computeRequiresBlocking(condition, groups.flatMap { group -> group.permissions.map { it.condition } })

    init {
        PathVariableScope.requireScoped(this)
    }

    /** This app's permissions as engine input, adapted once (RFC 0002 §3.3). */
    @Transient
    @Volatile
    private var engineRules: AppPermissionRules? = null

    internal fun engineRules(): AppPermissionRules =
        engineRules ?: AppPermissionRules(this, shared = true).also { engineRules = it }

    @delegate:Transient
    override val permissionIndexer: Map<PermissionId, Permission> by lazy(this) {
        super.permissionIndexer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppPermissionData

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
