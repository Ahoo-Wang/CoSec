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
package me.ahoo.cosec.context

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.token.TokenExpiredException
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger(SecurityContextParser::class.java)

/** Authorization header key */
const val AUTHORIZATION_HEADER_KEY = "authorization"

/**
 * Parser for extracting security context from requests.
 *
 * Implementations extract authentication information from incoming
 * requests and convert them to security contexts.
 *
 * @see DefaultSecurityContextParser
 */
fun interface SecurityContextParser {
    /**
     * Parses a request to extract security context.
     *
     * @param request The incoming request
     * @return The parsed security context
     * @throws TokenExpiredException if token has expired
     */
    @Throws(TokenExpiredException::class)
    fun parse(request: Request): SecurityContext

    /**
     * Parses a request, returning anonymous context on failure.
     *
     * @param request The incoming request
     * @return The parsed security context, or anonymous if parsing fails
     */
    fun ensureParse(request: Request): SecurityContext =
        try {
            parse(request)
        } catch (ignored: Throwable) {
            if (LOG.isDebugEnabled) {
                LOG.debug(ignored.message, ignored)
            }
            SimpleSecurityContext.anonymous()
        }
}
