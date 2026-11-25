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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Instant

/**
 * The kinds of credential (`IDN-44`) each version declares and reads: one copy for both sides, since 8.0.0-beta.9, the
 * previous release, has them too.
 */
internal object Kinds {
    /** CoSec's own token's credential, a person's. */
    fun jwt(id: String, expiresAt: Instant, issuedAt: Instant, session: TokenSession? = null): Identity.Credential =
        Identity.Credential(
            Identity.Method.JWT,
            Identity.Credential.Kind.PERSON,
            id,
            expiresAt,
            issuedAt = issuedAt,
            session = session
        )

    /** An API key's credential, a workload's by construction. */
    fun apiKey(id: String, expiresAt: Instant, capped: Boolean): Identity.Credential =
        Identity.Credential(Identity.Method.API_KEY, Identity.Credential.Kind.WORKLOAD, id, expiresAt, capped = capped)

    /** A person's sign-in, as a login declares it (`IDN-45`). */
    fun person(id: String, tenant: String, app: String, roles: Set<String>, attributes: Map<String, Any>): TokenSubject =
        TokenSubject(
            id,
            Identity.Credential.Kind.PERSON,
            tenant = tenant,
            app = app,
            roles = roles,
            attributes = attributes
        )

    /** Whose [credential] is, as this version read it: `person` or `workload`. */
    fun of(credential: Identity.Credential): String = credential.kind.name.lowercase()

    /** Whose [token] is, as this version's verifier read it (`IDN-46`): `person` or `workload`. */
    fun of(token: VerifiedToken): String = token.kind.name.lowercase()

    /** Whose the approval of the change record [bytes] says it was (`APR-9`): its kind's name, or `none`. */
    fun approvalKind(bytes: ByteArray): String = ChangeRequestCodec.decode(bytes).approval?.kind?.name ?: "none"
}
