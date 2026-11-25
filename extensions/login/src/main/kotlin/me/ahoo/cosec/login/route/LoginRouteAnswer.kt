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

package me.ahoo.cosec.login.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.routeProblem

/**
 * What a login route the starter serves answers ([RefreshRoute], [LogoutRoute]): a status, the body's media type and the
 * body, which every web stack writes as it is, with `Cache-Control: [CACHE_CONTROL]` on every answer (`IDN-23`,
 * `IDN-24`). A body is a composite token, a problem's body ([ProblemBodies], the host's renderer or CoSec's own,
 * `PIP-34`) or nothing: never anything the request carried but its request id.
 */
@InternalCoSecApi
class LoginRouteAnswer private constructor(
    val status: Int,
    /** The media type of [body], null when there is none. */
    val contentType: String?,
    val body: ByteArray,
) {
    @InternalCoSecApi
    companion object {
        /** On every answer: a token pair, or a refusal of one, is never cached. */
        const val CACHE_CONTROL = "no-store"

        /**
         * The most a request body may hold: two tokens, the access token of which must also fit an `Authorization`
         * header, which Tomcat and Netty cap at 8 KiB by default. A larger body is `400` without being read further.
         */
        const val MAX_BODY_BYTES = 32 * 1024

        /** A larger body than [MAX_BODY_BYTES]: the route answers it without reading it. */
        fun tooLarge(requestId: String, bodies: ProblemBodies): LoginRouteAnswer =
            problem(ProblemType.INVALID_REQUEST, requestId, bodies)

        /**
         * A route mounted where CoSec does not decide its requests: there is no request id to answer with, and nothing
         * is read or issued.
         */
        val NOT_UNDER_COSEC: LoginRouteAnswer = LoginRouteAnswer(
            ProblemType.INTERNAL_ERROR.status,
            null,
            ByteArray(0),
        )

        internal val NO_CONTENT: LoginRouteAnswer = LoginRouteAnswer(NO_CONTENT_STATUS, null, ByteArray(0))

        /** The pair as `CompositeTokenJsonTest` pins it, written by the one mapper: a token is never concatenated in. */
        internal fun token(token: CompositeToken): LoginRouteAnswer = LoginRouteAnswer(
            OK_STATUS,
            JSON,
            CoSecJson.mapper.writeValueAsBytes(
                linkedMapOf("accessToken" to token.accessToken, "refreshToken" to token.refreshToken),
            ),
        )

        internal fun problem(type: ProblemType, requestId: String, bodies: ProblemBodies): LoginRouteAnswer =
            LoginRouteAnswer(type.status, ProblemEncoder.MEDIA_TYPE, bodies.body(routeProblem(type, requestId)))

        private const val OK_STATUS = 200
        private const val NO_CONTENT_STATUS = 204
        private const val JSON = "application/json"
    }
}
