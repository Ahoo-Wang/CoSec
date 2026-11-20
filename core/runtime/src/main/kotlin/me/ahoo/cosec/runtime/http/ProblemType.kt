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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.format.ProblemTypes

/**
 * The problem vocabulary (RFC 0006 §6.1, Q27): one row per thing a client can do about it. Explicit and implicit
 * denies are both [UNAUTHORIZED] or [FORBIDDEN], so a response never tells a caller that a DENY exists. The rows are
 * `cosec-document`'s `ProblemTypes`, which the `problem` format descriptor is generated from: a constant is its slug
 * in UPPER_SNAKE case, its title and status are those of the row, and a constant with no row fails when the class
 * loads. `ProblemSchemaTest` fails for a row with no constant.
 * A set that grows: a minor release may add a member (RFC 0010 Q6), so a `when` over it needs an `else`, and an
 * implementation of a listener or a store never matches it exhaustively.
 */
enum class ProblemType {
    INVALID_REQUEST,
    UNAUTHORIZED,
    TOKEN_INVALID,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    FORBIDDEN,
    SCOPE_REJECTED,
    TOO_MANY_REQUESTS,
    UNAVAILABLE,
    INTERNAL_ERROR,
    ;

    val slug: String = name.lowercase().replace('_', '-')
    val title: String
    val status: Int

    init {
        val row = ProblemTypes.of(slug) ?: error("The problem type $name has no row in ProblemTypes.")
        title = row.title
        status = row.status
    }

    /** The stable UPPER_SNAKE code, one-to-one with [slug]. */
    val code: String get() = name

    val uri: String get() = TYPE_PREFIX + slug

    companion object {
        /** Each URI resolves to the anchor of the same name in the troubleshooting guide (§6.2). */
        const val TYPE_PREFIX = ProblemTypes.TYPE_PREFIX
    }
}
