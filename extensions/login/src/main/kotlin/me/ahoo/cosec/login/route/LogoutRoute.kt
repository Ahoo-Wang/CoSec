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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.login.route

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenRevoker
import kotlin.coroutines.cancellation.CancellationException

/**
 * The logout route the starter serves at `cosec.authentication.login.logout.path` (`IDN-24`): the pair in the body,
 * as a refresh takes it, so a client logs out whether or not its access token has expired ([TokenRevoker.revoke] of the
 * pair). `204` when the session is now ended, with the login it was switched from (`IDN-40`), and when it already was
 * (`token-revoked`): the end the caller asked for, so a logout whose answer was lost can be sent again. A pair that is not one is `401`, an unavailable store
 * `503`, an error `500`.
 */
@InternalCoSecApi
class LogoutRoute(
    private val revoker: TokenRevoker,
    /** The body of a refusal (`PIP-34`): the host's renderer, or CoSec's own. */
    private val bodies: ProblemBodies = ProblemBodies.DEFAULT,
) {
    /** Answers a request whose body is [body] and whose `Content-Type` is [contentType]. */
    // stays: every failure of a logout is answered, typed by RouteFailure; only cancellation propagates
    @Suppress("TooGenericExceptionCaught")
    suspend fun answer(contentType: String?, body: ByteArray, requestId: String): LoginRouteAnswer {
        val pair = TokenPairBody.read(contentType, body)
            ?: return LoginRouteAnswer.problem(ProblemType.INVALID_REQUEST, requestId, bodies)
        try {
            revoker.revoke(pair)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TokenRevokedException) {
            // Already ended: the caller's request is answered as done.
        } catch (e: Exception) {
            return RouteFailure.answer(ROUTE, e, requestId, bodies)
        }
        return LoginRouteAnswer.NO_CONTENT
    }

    private companion object {
        const val ROUTE = "logout"
    }
}
