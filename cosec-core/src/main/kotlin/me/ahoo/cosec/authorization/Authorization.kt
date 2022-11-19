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
package me.ahoo.cosec.authorization

import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.request.Request
import reactor.core.publisher.Mono

/**
 * The authorization refers to the process that determines what a user is allowed to do.
 *
 * @author ahoo wang
 */
fun interface Authorization {
    /**
     * 判断当前安全上下文（用户）是否具有该操作的权限.
     *
     * @param context Security Context
     * @param request Request
     * @return If true, the current user has access to the action.
     */
    fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult>
}

interface AuthorizeResult {
    val authorized: Boolean
    val reason: String

    companion object {
        val ALLOW: AuthorizeResult = AuthorizeResultData(true, "Allowed")
        val EXPLICIT_DENY: AuthorizeResult = AuthorizeResultData(false, "Explicit Denied")
        val IMPLICIT_DENY: AuthorizeResult = AuthorizeResultData(false, "Implicit Denied")
        fun allow(reason: String): AuthorizeResult = AuthorizeResultData(true, reason)
        fun deny(reason: String): AuthorizeResult = AuthorizeResultData(false, reason)
    }
}

internal data class AuthorizeResultData(
    override val authorized: Boolean,
    override val reason: String
) : AuthorizeResult
