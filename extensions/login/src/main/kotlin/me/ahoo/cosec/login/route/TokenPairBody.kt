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
import tools.jackson.core.JacksonException

/**
 * The body of a refresh or a logout: exactly `{"accessToken": "…", "refreshToken": "…"}` as `application/json`, two
 * non-empty strings and nothing else. Anything else is not read as a pair: an unknown or repeated member (the mapper
 * refuses duplicates), a value that is missing, empty or not a string, a body that is not one JSON object, another media
 * type or another charset. What was wrong is never told back: the answer carries no request input.
 */
internal object TokenPairBody {
    private val MEMBERS = setOf("accessToken", "refreshToken")
    private const val JSON = "application/json"

    /** The pair of [body], or null when it is not exactly one. */
    fun read(contentType: String?, body: ByteArray): CompositeToken? {
        if (!isJson(contentType)) return null
        val node = try {
            CoSecJson.mapper.readTree(body)
        } catch (_: JacksonException) {
            return null
        }
        if (node == null || !node.isObject || node.propertyNames().toSet() != MEMBERS) return null
        val accessToken = node.get("accessToken")
        val refreshToken = node.get("refreshToken")
        if (!accessToken.isString || !refreshToken.isString) return null
        val access = accessToken.stringValue()
        val refresh = refreshToken.stringValue()
        if (access.isEmpty() || refresh.isEmpty()) return null
        return CompositeToken(access, refresh)
    }

    /** `application/json`, with no parameter but `charset=utf-8`. */
    private fun isJson(contentType: String?): Boolean {
        val parts = contentType?.split(';')?.map { it.trim().lowercase() } ?: return false
        if (parts.first() != JSON) return false
        return parts.drop(1).all { it == "charset=utf-8" || it == "charset=\"utf-8\"" }
    }
}
