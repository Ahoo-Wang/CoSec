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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Instant

/**
 * What a new token says about whom it is issued for: the one input of a [TokenConverter], an immutable value.
 *
 * [roles] holds what the principal may hold in the token's [app], as `role` or `role@space` (RFC 0012 §3.5).
 *
 * The arguments after [id] are written by name, as the documentation's examples do: a token's scope reads better named.
 */
class TokenSubject(
    /** The principal's id: the token's subject. Not empty, and not beginning with `(`, which is CoSec's (`IDN-12`). */
    val id: String,
    /** The principal's tenant; null puts it in the default tenant. */
    val tenant: String? = null,
    /**
     * The app the token is for (`aud`), null for none, as on a host without apps: a host with an app refuses a token
     * declaring another or none (`SCP-11`), so leaving it out never makes a token valid where it was not.
     */
    val app: String? = null,
    val roles: Set<String> = emptySet(),
    val policies: Set<String> = emptySet(),
    val attributes: Map<String, Any> = emptyMap(),
    /**
     * The `cosec.membership` claim: [MembershipClaims.PROVIDER][me.ahoo.cosec.spi.membership.MembershipClaims.PROVIDER]
     * to have the `MembershipProvider` answer for the token's memberships, null when the token carries its own.
     */
    val membershipMarker: String? = null,
    /**
     * The session the pair belongs to (`sid`, `auth_time`, `cosec.generation`), null to start one: issuing through
     * [TokenCompositeAuthentication] starts a family for a subject without one, signed in now. A refresh and a tenant
     * switch answer theirs, from a session they checked is live (`IDN-22`).
     */
    val session: TokenSession? = null,
    /** The latest the pair may be valid until: its session's end. Null is no bound but the converter's validities. */
    val notAfter: Instant? = null,
) {
    private fun fields(): List<Any?> =
        listOf(id, tenant, app, roles, policies, attributes, membershipMarker, session, notAfter)

    /** This subject in [issue]'s session, bounded by its end. */
    fun inSession(issue: TokenFamilies.Issue): TokenSubject = TokenSubject(
        id = id,
        tenant = tenant,
        app = app,
        roles = roles,
        policies = policies,
        attributes = attributes,
        membershipMarker = membershipMarker,
        session = issue.session,
        notAfter = issue.notAfter,
    )

    override fun equals(other: Any?): Boolean = other is TokenSubject && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    /** What identifies the subject's scope and how much it holds; never the attributes, which an application fills freely. */
    override fun toString(): String =
        "TokenSubject(id=$id, tenant=$tenant, app=$app, roles=${roles.size}, policies=${policies.size}, " +
            "attributes=${attributes.size}, session=${session != null})"

    companion object {
        /**
         * What the token [token] said, so that refreshing it says the same, its app and tenant included; in [issue]'s
         * session when one is given.
         */
        @JvmStatic
        @JvmOverloads
        fun of(token: VerifiedToken, issue: TokenFamilies.Issue? = null): TokenSubject = TokenSubject(
            id = token.principal,
            app = token.app,
            tenant = token.tenant,
            roles = token.roles,
            policies = token.policies,
            attributes = token.attributes,
            membershipMarker = token.membershipMarker,
            session = issue?.session,
            notAfter = issue?.notAfter,
        )
    }
}
