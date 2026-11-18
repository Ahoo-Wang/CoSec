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
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.runtime.http.ProblemType
import kotlin.coroutines.cancellation.CancellationException

/**
 * The refresh route the starter serves at `cosec.authentication.login.refresh.path` (`IDN-23`), whatever the web
 * stack: the pair in the body, a new pair of the same session out, or the problem that says why not. Its request has
 * already passed CoSec, as an anonymous caller (`PIP-6`); it reads nothing of it but the body and its request id, so
 * no header switches a tenant, an app or a space.
 */
@InternalCoSecApi
class RefreshRoute(
    private val refresh: RefreshTokenAuthentication<RefreshTokenCredentials>,
    private val converter: TokenConverter,
) {
    private class Presented(
        override val accessToken: String,
        override val refreshToken: String
    ) : RefreshTokenCredentials

    /** Answers a request whose body is [body] and whose `Content-Type` is [contentType]. */
    // stays: every failure of a refresh is answered, typed by RouteFailure; only cancellation propagates
    @Suppress("TooGenericExceptionCaught")
    suspend fun answer(contentType: String?, body: ByteArray, requestId: String): LoginRouteAnswer {
        val pair = TokenPairBody.read(contentType, body)
            ?: return LoginRouteAnswer.problem(ProblemType.INVALID_REQUEST, requestId)
        val token = try {
            converter.toToken(refresh.authenticate(Presented(pair.accessToken, pair.refreshToken)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RouteFailure.answer(ROUTE, e, requestId)
        }
        return LoginRouteAnswer.token(token)
    }

    private companion object {
        const val ROUTE = "refresh"
    }
}
