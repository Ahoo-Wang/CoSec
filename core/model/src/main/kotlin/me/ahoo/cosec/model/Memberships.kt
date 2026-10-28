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

package me.ahoo.cosec.model

/**
 * What a principal is a member of. Never part of a snapshot (P11): each request gets it from the token's claims or
 * from a `MembershipProvider` (RFC 0004 §4.5).
 */
class Memberships(
    val source: Source,
    /** The roles in each space, in claim order. The default space's key is [SpaceId.DEFAULT]. */
    val roles: Map<SpaceId, Set<RoleId>>,
    /** The policies the principal attaches directly (7.x `principal.policies`), in claim order. */
    val attachments: List<PolicyId>,
    /**
     * Claimed entries that are not well formed, such as `a@b@c`. Kept for the trace only: like `RoleScope`, they apply
     * in no scope.
     */
    val malformed: List<String> = emptyList(),
) {
    enum class Source { CLAIMS, PROVIDER }
}
