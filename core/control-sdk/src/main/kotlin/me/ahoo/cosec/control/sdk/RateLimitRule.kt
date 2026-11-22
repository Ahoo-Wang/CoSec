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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.core.JacksonException

/**
 * A rate limit rule of a document (the document format's `rateLimit`, `wiki/policies/rate-limiting.md`), kept as its
 * JSON: the control plane checks it when a change is proposed. [id] is read from it. Two rules are equal when their
 * JSON is, member order aside.
 */
@ExperimentalCoSecApi
class RateLimitRule private constructor(
    val id: String,
    /** The rule's JSON text, compact. */
    val json: String,
) {
    override fun equals(other: Any?): Boolean = other is RateLimitRule && tree(json) == tree(other.json)

    override fun hashCode(): Int = tree(json).hashCode()

    override fun toString(): String = json

    @ExperimentalCoSecApi
    companion object {
        /** The rule [json] writes; [IllegalArgumentException] when it is not a JSON object with a string `id`. */
        fun of(json: String): RateLimitRule {
            val tree = try {
                CoSecJson.mapper.readTree(json)
            } catch (e: JacksonException) {
                throw IllegalArgumentException("A rate limit rule is a JSON object.", e)
            }
            val id = tree?.takeIf { it.isObject }?.get("id")?.takeIf { it.isString }?.asString()
            requireNotNull(id) { "A rate limit rule is a JSON object with a string id." }
            return RateLimitRule(id, CoSecJson.mapper.writeValueAsString(tree))
        }

        private fun tree(json: String) = CoSecJson.mapper.readTree(json)
    }
}
