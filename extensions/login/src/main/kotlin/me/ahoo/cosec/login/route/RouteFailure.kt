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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.SessionStoreUnavailableException
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException

private val log = KotlinLogging.logger {}

/**
 * What a failure of a login route answers (`IDN-23`): a refusal of the pair is `401` with the type that names why;
 * a revocation read or a session store that cannot answer is `503` (`IDN-14`, `IDN-19`: nothing issued, nothing
 * spent); anything else is an error, `500`, as the pipeline answers an unmodelled one, so a bug never looks like an
 * outage. Neither `503` nor `500` ever ends a client's session. Nothing of the exception reaches the body, and the
 * log line names its class and where it was thrown, never its message, which may hold a token.
 */
internal object RouteFailure {
    fun answer(route: String, failure: Exception, requestId: String, bodies: ProblemBodies): LoginRouteAnswer {
        val type = typeOf(failure)
        when (type) {
            ProblemType.UNAVAILABLE -> log.warn {
                "The $route route could not reach the session store or the revocations (request $requestId): " +
                    failure.javaClass.name + (failure.cause?.let { ", caused by ${it.javaClass.name}" } ?: "") + "."
            }
            ProblemType.INTERNAL_ERROR -> log.error {
                "The $route route failed with an error (request $requestId): ${failure.javaClass.name} at " +
                    "${failure.stackTrace.firstOrNull()}."
            }
            else -> log.debug { "The $route route refused the pair (request $requestId): ${type.slug}." }
        }
        return LoginRouteAnswer.problem(type, requestId, bodies)
    }

    fun typeOf(failure: Exception): ProblemType = when (failure) {
        is TokenExpiredException -> ProblemType.TOKEN_EXPIRED
        is TokenRevokedException -> ProblemType.TOKEN_REVOKED
        is TokenVerificationException -> ProblemType.TOKEN_INVALID
        is RevocationUnavailableException, is SessionStoreUnavailableException -> ProblemType.UNAVAILABLE
        else -> ProblemType.INTERNAL_ERROR
    }
}
