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

package me.ahoo.cosec.document.identity

/**
 * Every member of the `CoSec-Identity` header, by its path from the root (`IDN-42`): what a reader takes its absence
 * for, and whether a reader that ignored it would decide more widely than one that read it. The one record of both;
 * the format descriptor (`cosec-identity`) names the same paths, and `IdentityMembersTest` holds the two together.
 *
 * Every member here is read by every 8.x reader. What the table records is the shape a member added later takes after:
 * one whose being ignored would widen what a reader decides can only be added as a member every reader must understand,
 * never as one an older reader may ignore; and a hop that did not receive a member never states it (the relay of a
 * request's caller leaves out `memberships` it was not forwarded).
 */
internal object IdentityMembers {
    /** What a reader takes a member's absence for. */
    enum class WhenAbsent {
        /** The header does not read: 400, or 401 for `auth.exp` (an unbounded credential). Within its parent. */
        REFUSED,

        /**
         * Not stated: a host that decides with policies on the identity refuses the header (400); a service that only
         * trusts the gateway reads the decided space's roles and no attachment.
         */
        UNSTATED,

        /** Read as false. */
        FALSE,

        /** Read as none. */
        NONE,

        /** Read as unknown, which whoever needs it takes in its restrictive sense. */
        UNKNOWN,

        /** Read as empty. */
        EMPTY,

        /** Read as a workload's credential: the restrictive reading of whose credential it is (`IDN-44`). */
        WORKLOAD,
    }

    /** One member: its [path], what its absence is read as, and whether ignoring it would widen what is decided. */
    class Member(val path: String, val whenAbsent: WhenAbsent, val ignoringWidens: Boolean)

    private fun required(path: String) = Member(path, WhenAbsent.REFUSED, ignoringWidens = false)

    val ALL: List<Member> = listOf(
        required("v"),
        required("tenant"),
        required("sub"),
        required("scope"),
        required("scope.app"),
        required("scope.space"),
        required("roles"),
        // The attachments and the other spaces' roles: a DENY attached to the principal, or on a role it holds in
        // another space, lapses for a reader that does not read them.
        Member("memberships", WhenAbsent.UNSTATED, ignoringWidens = true),
        required("memberships.spaces"),
        required("memberships.attachments"),
        required("auth"),
        required("auth.method"),
        // A workload's: it never approves, is never the trust root and has no break-glass, so a reader that ignored it
        // would decide no more widely than one that read it as absent.
        Member("auth.kind", WhenAbsent.WORKLOAD, ignoringWidens = false),
        required("auth.jti"),
        required("auth.exp"),
        // A revocation of the principal's tokens takes a credential that does not say when it was issued as revoked.
        Member("auth.iat", WhenAbsent.UNKNOWN, ignoringWidens = false),
        // Uncapped: the principal's own bindings apply, which a capped credential's do not.
        Member("auth.capped", WhenAbsent.FALSE, ignoringWidens = true),
        // No session: a tenant switch is refused, nothing granted.
        Member("auth.session", WhenAbsent.NONE, ignoringWidens = false),
        required("auth.session.sid"),
        required("auth.session.auth_time"),
        required("auth.session.generation"),
        required("iat"),
        required("rid"),
        // A DENY that reads a claim lapses for a reader that does not read it (an ALLOW does too, which narrows).
        Member("claims", WhenAbsent.EMPTY, ignoringWidens = true),
        // No member a reader must understand beyond those it knows (IDN-41).
        Member(IdentityCrit.MEMBER, WhenAbsent.EMPTY, ignoringWidens = false),
    )

    /** [ALL] by path. */
    val BY_PATH: Map<String, Member> = ALL.associateBy { it.path }
}
