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

package me.ahoo.cosec.snapshot

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.SpacedRoleId
import reactor.core.publisher.Mono

/**
 * Writes snapshot documents (RFC 0003 §4.6): the write operations 7.0 has, each a read-modify-write of exactly one
 * document that publishes it with the next revision. Phase 6's control plane replaces it.
 *
 * Concurrent writers of one document never lose an update: a write that raced another is retried from what the
 * other wrote. A write that changes nothing publishes nothing. A returned `Mono` that completes means this instance
 * reads the new revision from then on.
 */
interface SnapshotPublisher {
    /** Adds or replaces [policy] in the policies document. Replaces `PolicyRepository.setPolicy`. */
    fun setPolicy(policy: Policy): Mono<Void>

    /**
     * Adds or replaces every one of [policies] in one write of the policies document, all or none; an id may appear
     * once. For writers of many policies at once, such as `LocalPolicyInitializer` on every instance's start.
     */
    fun setPolicies(policies: Collection<Policy>): Mono<Void>

    /** Adds or replaces the app permission of [AppPermission.id], keeping the app's grants. */
    fun setAppPermission(appPermission: AppPermission): Mono<Void>

    /** Sets the grants of [roleId] in [spaceId] of [appId]. The app must have an app permission. */
    fun setRoleGrants(appId: AppId, roleId: RoleId, spaceId: SpaceId, permissionIds: Set<PermissionId>): Mono<Void>

    /** Sets the grants of every role in [grants] in one write of [appId]'s document, all or none. */
    fun setRoleGrants(appId: AppId, grants: Map<SpacedRoleId, Set<PermissionId>>): Mono<Void>

    /** Removes the grants of [roleId] in [spaceId] of [appId]; does nothing when there are none. */
    fun removeRoleGrants(appId: AppId, roleId: RoleId, spaceId: SpaceId): Mono<Void>
}
