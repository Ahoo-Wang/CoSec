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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * A policy of a document (the document format's `policy`): its [statements], the [condition] they all hold to, whether
 * a principal may be bound to it directly ([attachable]), and whether it is a boundary policy ([boundary], the platform
 * document's).
 */
@ExperimentalCoSecApi
class Policy(
    val id: String,
    statements: List<Statement>,
    val attachable: Boolean = false,
    val condition: Condition? = null,
    val boundary: Boolean = false,
) {
    val statements: List<Statement> = statements.toList()

    override fun equals(other: Any?): Boolean = other is Policy && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Policy",
        "id" to id,
        "statements" to statements,
        "attachable" to attachable,
        "condition" to condition,
        "boundary" to boundary,
    )

    private fun parts(): List<Any?> = listOf(id, statements, attachable, condition, boundary)
}
