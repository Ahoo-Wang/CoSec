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

package me.ahoo.cosec.explain

import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads each repository answer once, so the real decision and the trace of one explanation see the same data
 * even when policies change in between.
 */
internal class SnapshotPolicyRepository(private val delegate: PolicyRepository) : PolicyRepository {
    private val globalPolicies: Mono<List<Policy>> = Mono.defer { delegate.getGlobalPolicy() }.cache()
    private val policies = ConcurrentHashMap<Set<String>, Mono<List<Policy>>>()

    override fun getGlobalPolicy(): Mono<List<Policy>> = globalPolicies

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
        policies.computeIfAbsent(policyIds) { Mono.defer { delegate.getPolicies(it) }.cache() }

    override fun setPolicy(policy: Policy): Mono<Void> =
        Mono.error(UnsupportedOperationException("An explanation never writes policies."))
}

internal class SnapshotAppRolePermissionRepository(
    private val delegate: AppRolePermissionRepository
) : AppRolePermissionRepository {
    private data class Key(val appId: String, val spaceId: String, val roleIds: Set<RoleId>)

    private val appRolePermissions = ConcurrentHashMap<Key, Mono<AppRolePermission>>()

    override fun getAppRolePermission(
        appId: String,
        spaceId: String,
        roleIds: Set<RoleId>
    ): Mono<AppRolePermission> =
        appRolePermissions.computeIfAbsent(Key(appId, spaceId, roleIds)) { key ->
            Mono.defer { delegate.getAppRolePermission(key.appId, key.spaceId, key.roleIds) }.cache()
        }
}
