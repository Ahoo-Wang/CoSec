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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import tools.jackson.databind.JsonNode

/**
 * One side of a [DecisionReplay], built from the export. An [AuthorizationFactory] is one, over repositories holding
 * the export; RFC 0003 §8.2 builds its candidate from the exported JSON instead, through the 7.x reader, the publisher
 * and the snapshot store (`cosec-cocache`'s `SnapshotReplaySide`).
 *
 * An authorization that is also [AutoCloseable] is closed when the replay ends.
 */
fun interface ReplaySide {
    fun replaying(
        export: ReplayExport,
        blacklistChecker: BlacklistChecker,
        rootPrincipalMatcher: RootPrincipalMatcher
    ): DecisionAuthorization
}

/**
 * What a replay read from the export, for its [ReplaySide]s: the exported JSON as it is, and repositories holding it.
 *
 * Both sides load the policies in the same order, by id (RFC 0003 §7 X3): 7.0 reads global policies in the order of a
 * Redis set, which is not defined, and a snapshot document lists them by id.
 */
class ReplayExport internal constructor(
    /** Every exported policy, by id. */
    val policies: List<ExportedPolicy>,
    /** Every exported app, in export order. */
    val apps: List<ExportedApp>,
    val policyRepository: PolicyRepository,
    val appRolePermissionRepository: AppRolePermissionRepository,
)

/**
 * An exported policy.
 *
 * @param json the policy as exported
 * @param global whether it is of type `GLOBAL`, so in 7.0's global policy index
 */
class ExportedPolicy(val id: String, val json: JsonNode, val global: Boolean) {
    internal constructor(json: JsonNode, policy: Policy) : this(policy.id, json, policy.type == PolicyType.GLOBAL)
}

/**
 * An exported app.
 *
 * @param appPermission the app permission as exported, null for an app exported without one
 * @param grants each `role` or `role@space` key with its permission ids as exported
 */
class ExportedApp(val appId: String, val appPermission: JsonNode?, val grants: Map<String, JsonNode>)
