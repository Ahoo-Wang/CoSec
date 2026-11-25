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

import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Instant

/**
 * The previous release's copy of [Kinds] (the current one is in src/current/kotlin): a release before kinds (`IDN-44`),
 * whose credentials declare none and whose reader knows of none.
 */
internal object Kinds {
    fun jwt(id: String, expiresAt: Instant, issuedAt: Instant, session: TokenSession? = null): Identity.Credential =
        Identity.Credential(Identity.Method.JWT, id, expiresAt, issuedAt = issuedAt, session = session)

    fun apiKey(id: String, expiresAt: Instant, capped: Boolean): Identity.Credential =
        Identity.Credential(Identity.Method.API_KEY, id, expiresAt, capped = capped)

    /** A sign-in, which a release before kinds does not declare. */
    fun person(id: String, tenant: String, app: String, roles: Set<String>, attributes: Map<String, Any>): TokenSubject =
        TokenSubject(id, tenant = tenant, app = app, roles = roles, attributes = attributes)

    /** A release before kinds reads none. */
    @Suppress("UNUSED_PARAMETER")
    fun of(credential: Identity.Credential): String = "none"

    /** A release before kinds reads none from a token either. */
    @Suppress("UNUSED_PARAMETER")
    fun of(token: VerifiedToken): String = "none"
}
