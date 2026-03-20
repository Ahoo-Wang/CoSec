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
package me.ahoo.cosec.api.authorization

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import reactor.core.publisher.Mono

/**
 * Authorization service interface.
 *
 * Authorization is the process of determining what a user is allowed to do.
 * It evaluates policies and permissions to decide whether a request should
 * be allowed or denied.
 *
 * The authorization process typically checks:
 * 1. Global policies (apply to all applications)
 * 2. Principal-specific policies (user-defined policies)
 * 3. Role-based permissions (app-specific role permissions)
 *
 * @see AuthorizeResult
 * @see SecurityContext
 * @see Request
 */
fun interface Authorization {
    /**
     * Determines whether the current user has permission to perform the requested action.
     *
     * @param request The incoming request to authorize
     * @param context The security context containing user information
     * @return [Mono] emitting [AuthorizeResult] indicating whether access is granted
     */
    fun authorize(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult>
}
