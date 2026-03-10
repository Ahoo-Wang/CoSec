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

package me.ahoo.cosec.blacklist

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import reactor.core.publisher.Mono

/**
 * Interface for checking if a request is blocked by blacklist.
 *
 * Blacklist checking is used to block specific users or IP addresses
 * from accessing the system, typically for security reasons like
 * suspicious activity or policy violations.
 *
 * @see NoOp
 */
interface BlacklistChecker {
    /**
     * Checks if the request is allowed based on blacklist rules.
     *
     * @param request The incoming request
     * @param context The security context
     * @return [Mono] emitting true if the request is allowed, false if blocked
     */
    fun check(
        request: Request,
        context: SecurityContext
    ): Mono<Boolean>

    /**
     * No-op blacklist checker that always allows all requests.
     * Use this when blacklist functionality is not needed.
     */
    object NoOp : BlacklistChecker {
        private val PASS = Mono.just(true)

        override fun check(
            request: Request,
            context: SecurityContext
        ): Mono<Boolean> = PASS
    }
}
