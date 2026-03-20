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

/**
 * Result of authorization decision.
 *
 * This interface represents the outcome of an authorization check,
 * including whether the request is authorized and the reason for the decision.
 *
 * @see Authorization
 * @see VerifyResult
 */
interface AuthorizeResult {
    /** Whether the request is authorized */
    val authorized: Boolean

    /** The reason for this authorization decision */
    val reason: String

    companion object {
        private const val ALLOW_REASON = "Allow"
        private const val EXPLICIT_DENY_REASON = "Explicit Deny"
        private const val IMPLICIT_DENY_REASON = "Implicit Deny"
        private const val TOKEN_EXPIRED_REASON = "Token Expired"
        private const val TOO_MANY_REQUESTS_REASON = "Too Many Requests"

        /** Authorization allowed */
        val ALLOW: AuthorizeResult = allow(ALLOW_REASON)

        /** Authorization explicitly denied */
        val EXPLICIT_DENY: AuthorizeResult = deny(EXPLICIT_DENY_REASON)

        /** Authorization implicitly denied (no matching policy) */
        val IMPLICIT_DENY: AuthorizeResult = deny(IMPLICIT_DENY_REASON)

        /** Token has expired */
        val TOKEN_EXPIRED: AuthorizeResult = deny(TOKEN_EXPIRED_REASON)

        /** Rate limit exceeded */
        val TOO_MANY_REQUESTS: AuthorizeResult = deny(TOO_MANY_REQUESTS_REASON)

        /**
         * Creates an allowed result with the given reason.
         *
         * @param reason The reason for allowing
         * @return An allowed [AuthorizeResult]
         */
        fun allow(reason: String): AuthorizeResult = AuthorizeResultData(true, reason)

        /**
         * Creates a denied result with the given reason.
         *
         * @param reason The reason for denying
         * @return A denied [AuthorizeResult]
         */
        fun deny(reason: String): AuthorizeResult = AuthorizeResultData(false, reason)
    }
}

/**
 * Internal data class for [AuthorizeResult] implementation.
 */
internal data class AuthorizeResultData(
    override val authorized: Boolean,
    override val reason: String
) : AuthorizeResult
