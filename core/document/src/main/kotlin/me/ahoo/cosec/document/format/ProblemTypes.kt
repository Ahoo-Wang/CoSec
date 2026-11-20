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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The problem vocabulary (RFC 0006 §6.1, Q27), written once: one row per problem type CoSec answers with, and the
 * prefix of their `type` URIs. `DocumentFormats.problemSchema` is generated from it, and `cosec-runtime`'s `ProblemType`
 * is built over it (a constant of the enum with no row fails when the class loads, and `ProblemSchemaTest` fails for a
 * row with no constant). A new type is one row here, one constant there, a file in `contract/problems/` and a section of
 * the troubleshooting guide with the slug's anchor (`pnpm check:problems`).
 *
 * A separate object, so that the runtime reads the rows without loading every format descriptor of [DocumentFormats].
 */
@InternalCoSecApi
object ProblemTypes {
    /** Each problem `type` URI is this prefix and a slug; it resolves to the troubleshooting anchor of that name. */
    const val TYPE_PREFIX = "https://cosec.ahoo.me/problems/"

    /** One problem type: its slug, title and status. The `code` is the slug in UPPER_SNAKE case. */
    @InternalCoSecApi
    data class Row(val slug: String, val title: String, val status: Int) {
        val code: String get() = slug.uppercase().replace('-', '_')
    }

    val rows: List<Row> = listOf(
        Row("invalid-request", "Invalid Request", 400),
        Row("unauthorized", "Unauthorized", 401),
        Row("token-invalid", "Token Invalid", 401),
        Row("token-expired", "Token Expired", 401),
        Row("token-revoked", "Token Revoked", 401),
        Row("forbidden", "Forbidden", 403),
        Row("scope-rejected", "Scope Rejected", 403),
        Row("too-many-requests", "Too Many Requests", 429),
        Row("unavailable", "Service Unavailable", 503),
        Row("internal-error", "Internal Server Error", 500),
    )

    /** The row of [slug], or null. */
    fun of(slug: String): Row? = rows.firstOrNull { it.slug == slug }
}
