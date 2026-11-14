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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The request path within the application (`PIP-20`), the one function every adapter derives [HttpFacts.rawPath] with:
 * the request URI as received, without the context path. Servers write the context path differently: Tomcat as it
 * appears in the request (`/%61pp`), Jetty in its canonical form (`/app`) while the URI keeps the client's `/%61pp`.
 * Removing the context path as text would then remove nothing, and `/app/orders/1` would be decided where the handler
 * serves `/orders/1`, past every statement on `/orders`.
 */
@InternalCoSecApi
object ApplicationPaths {
    /**
     * What a request whose URI does not agree with its context path is decided on: a malformed escape, which
     * [PathNormalizer] refuses, so the request is 400 `invalid-request`.
     */
    const val UNREADABLE = "/%"

    /**
     * [requestUri], still encoded, without [contextPath]: the leading segments of [requestUri] that are, without path
     * parameters and decoded once, the segments of [contextPath] taken the same way. [UNREADABLE] when they are not.
     */
    fun within(requestUri: String, contextPath: String): String {
        if (contextPath.isEmpty()) {
            return requestUri
        }
        if (requestUri.startsWith(contextPath) &&
            (requestUri.length == contextPath.length || requestUri[contextPath.length] == '/')
        ) {
            return requestUri.substring(contextPath.length)
        }
        val context = contextPath.split('/')
        val uri = requestUri.split('/')
        if (uri.size < context.size) {
            return UNREADABLE
        }
        for (index in context.indices) {
            val segment = valueOf(uri[index]) ?: return UNREADABLE
            if (segment != valueOf(context[index])) {
                return UNREADABLE
            }
        }
        val rest = uri.subList(context.size, uri.size)
        return if (rest.isEmpty()) "" else rest.joinToString("/", prefix = "/")
    }

    /** A segment as a server matches it: without path parameters, decoded once; null when it does not decode. */
    private fun valueOf(
        segment: String
    ): String? = PercentDecoder.decode(segment.substringBefore(';'), plusIsSpace = false)
}
