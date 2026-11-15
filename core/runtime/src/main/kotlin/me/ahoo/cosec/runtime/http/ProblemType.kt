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

/**
 * The problem vocabulary (RFC 0006 §6.1, Q27): one row per thing a client can do about it. Explicit and implicit
 * denies are both [UNAUTHORIZED] or [FORBIDDEN], so a response never tells a caller that a DENY exists. The `problem`
 * format descriptor in `DocumentFormats` lists the same rows, and `ProblemSchemaTest` keeps them equal.
 * A set that grows: a minor release may add a member (RFC 0010 Q6), so a `when` over it needs an `else`, and an
 * implementation of a listener or a store never matches it exhaustively.
 */
enum class ProblemType(val slug: String, val title: String, val status: Int) {
    INVALID_REQUEST("invalid-request", "Invalid Request", 400),
    UNAUTHORIZED("unauthorized", "Unauthorized", 401),
    TOKEN_INVALID("token-invalid", "Token Invalid", 401),
    TOKEN_EXPIRED("token-expired", "Token Expired", 401),
    TOKEN_REVOKED("token-revoked", "Token Revoked", 401),
    FORBIDDEN("forbidden", "Forbidden", 403),
    SCOPE_REJECTED("scope-rejected", "Scope Rejected", 403),
    TOO_MANY_REQUESTS("too-many-requests", "Too Many Requests", 429),
    UNAVAILABLE("unavailable", "Service Unavailable", 503),
    INTERNAL_ERROR("internal-error", "Internal Server Error", 500),
    ;

    /** The stable UPPER_SNAKE code, one-to-one with [slug]. */
    val code: String get() = name

    val uri: String get() = TYPE_PREFIX + slug

    companion object {
        /** Each URI resolves to the anchor of the same name in the troubleshooting guide (§6.2). */
        const val TYPE_PREFIX = "https://cosec.ahoo.me/problems/"
    }
}
