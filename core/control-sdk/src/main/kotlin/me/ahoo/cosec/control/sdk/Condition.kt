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
 * A condition of a policy, a statement or a rate limit rule, as the document format writes it: a JSON object of the
 * condition language (`wiki/policies/language.md`), such as `{"eq":["subject.tenant",{"attr":"resource.var.t"}]}`. It
 * stays JSON in the sdk: the control plane checks it when a change is proposed, and a wrong one is the change's
 * refusal. Two conditions are equal when their JSON is, member order aside.
 */
@ExperimentalCoSecApi
class Condition private constructor(
    /** The condition's JSON text, compact. */
    val json: String,
) {
    override fun equals(other: Any?): Boolean = other is Condition && tree(json) == tree(other.json)

    override fun hashCode(): Int = tree(json).hashCode()

    override fun toString(): String = json

    @ExperimentalCoSecApi
    companion object {
        /** The condition [json] writes; [IllegalArgumentException] when it is not a JSON object. */
        fun of(json: String): Condition {
            val tree = try {
                CoSecJson.mapper.readTree(json)
            } catch (e: JacksonException) {
                throw IllegalArgumentException("A condition is a JSON object.", e)
            }
            require(tree != null && tree.isObject) { "A condition is a JSON object." }
            return Condition(CoSecJson.mapper.writeValueAsString(tree))
        }

        private fun tree(json: String) = CoSecJson.mapper.readTree(json)
    }
}
