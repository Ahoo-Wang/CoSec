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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.clienthost

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.login.token.SessionStoreUnavailableException
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.routeProblem
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/** Where the application's tenant switch is: `POST /auth/switch/{tenant}`, the pair in the body. */
internal const val SWITCH_PATH = "/auth/switch"

/** The tenant the host's switch refuses as the application would refuse a non-member: a `403` that is not CoSec's. */
internal const val CLOSED_TENANT = "closed"

/** The switch a client asks for: into [targetTenantId], from the request's session, with the request's pair (`IDN-22`). */
data class ClientHostSwitch(
    override val targetTenantId: String,
    override val context: SecurityContext,
    override val accessToken: String,
    override val refreshToken: String,
) : SwitchTenantCredentials

/** The application's refusal of a switch: the user is not a member of the target tenant. */
internal class NotAMember : IllegalArgumentException("Not a member of this tenant.")

/** The application's switch over CoSec's `SwitchTenantAuthentication`: every tenant but [CLOSED_TENANT], no roles there. */
internal fun clientHostSwitchAuthentication(
    verifier: TokenVerifier,
    families: TokenFamilies,
    revocation: TokenRevocation,
) = SwitchTenantAuthentication(verifier, families, revocation, ClientHostSwitch::class.java) { credentials ->
    if (credentials.targetTenantId == CLOSED_TENANT) throw NotAMember()
    TenantMembership()
}

/**
 * What the host's switch route answers, as `wiki/integration/authentication.md` asks of an application's switch: the
 * pair; a refused pair `401` with the type that names why; the revocations or the session store that cannot answer
 * `503`; its own refusal of a non-member `403` with a body of its own. Like the served refresh route, a body without the
 * pair is `400 invalid-request`, and every answer is `Cache-Control: no-store`.
 */
internal suspend fun answerSwitch(
    auth: TokenCompositeAuthentication,
    tenant: String,
    body: Map<String, Any?>,
    context: SecurityContext,
): ResponseEntity<Any> {
    val accessToken = body["accessToken"] as? String
    val refreshToken = body["refreshToken"] as? String
    if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty()) {
        return problem(ProblemType.INVALID_REQUEST, context)
    }
    return try {
        val pair = auth.authenticateAsToken(ClientHostSwitch(tenant, context, accessToken, refreshToken))
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(mapOf("accessToken" to pair.accessToken, "refreshToken" to pair.refreshToken))
    } catch (_: NotAMember) {
        ResponseEntity.status(FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(mapOf("error" to "not a member"))
    } catch (e: TokenVerificationException) {
        problem(
            when (e) {
                is TokenExpiredException -> ProblemType.TOKEN_EXPIRED
                is TokenRevokedException -> ProblemType.TOKEN_REVOKED
                else -> ProblemType.TOKEN_INVALID
            },
            context,
        )
    } catch (_: RevocationUnavailableException) {
        problem(ProblemType.UNAVAILABLE, context)
    } catch (_: SessionStoreUnavailableException) {
        problem(ProblemType.UNAVAILABLE, context)
    }
}

private const val NO_STORE = "no-store"
private const val FORBIDDEN = 403

private fun problem(type: ProblemType, context: SecurityContext): ResponseEntity<Any> =
    ResponseEntity.status(type.status)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .contentType(MediaType.parseMediaType(ProblemEncoder.MEDIA_TYPE))
        .body(ProblemEncoder.encode(routeProblem(type, context.requestId)))
