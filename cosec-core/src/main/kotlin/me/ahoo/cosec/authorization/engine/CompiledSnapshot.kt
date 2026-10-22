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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.snapshot.Snapshot

/**
 * One entry of a compiled document (RFC 0003 §3.4): adapted, or [Broken] when it cannot be decoded. A broken entry
 * fails only the layers that read it, as an unreadable cache entry does in 7.0 (§4.4 F4).
 */
internal sealed interface Compiled<out T : Any> {
    class Ok<out T : Any>(val value: T) : Compiled<T>

    class Broken(val error: Throwable) : Compiled<Nothing>
}

/** A compiled [me.ahoo.cosec.snapshot.SnapshotKey.Policies] document. Immutable and shared by every request. */
internal class CompiledPolicies(
    val revision: Long,
    /**
     * The global layer, built once: the global policies' groups in document order, or [LayerInput.Unavailable] when
     * a policy that may be global is broken (7.0's read of the global policies fails as a whole).
     */
    val globalLayer: LayerInput,
    /** Every policy, global or not, by id: what the principal layer looks up. */
    val byId: Map<PolicyId, Compiled<PolicyGroup>>,
)

/** A compiled [me.ahoo.cosec.snapshot.SnapshotKey.App] document. Immutable and shared by every request. */
internal class CompiledApp(
    val revision: Long,
    val appId: AppId,
    /** The app permission's rules, shared by every request ([AppPermissionRules.shared]). */
    val appPermission: Compiled<AppPermissionRules>,
    /**
     * Each role's grants by space, then by bare role id, in the order the document lists them. Nested so that a
     * request looks its roles up without building a key per role.
     */
    val grants: Map<SpaceId, Map<RoleId, Compiled<RolePermission>>>,
)

/** The documents one evaluation reads (RFC 0003 §3.4): one immutable revision of each. */
internal typealias PolicySnapshot = Snapshot<CompiledPolicies, CompiledApp>
