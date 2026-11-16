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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import tools.jackson.databind.node.JsonNodeFactory

/**
 * The digest of a statement's content as the document format writes it, without any confirmation it carries, together
 * with the condition of its policy, which guards it as much as its own: what a [Confirmation] names. A statement, or a
 * policy condition, that changes no longer matches the confirmation given for it (review K4). One shape: the content
 * is `{statement, policyCondition}`, `policyCondition` being `null` for a policy without a condition.
 */
internal object StatementDigest {
    fun of(statement: StatementSource, policyCondition: Condition? = null): String {
        val bare = PolicyDefinition(PolicyId("statement"), false, policyCondition, listOf(unconfirmed(statement)))
        val tree = PolicyDocumentCodec.policyTree(bare)
        val content = JsonNodeFactory.instance.objectNode()
        content.set("statement", tree.get("statements").get(0))
        content.set("policyCondition", tree.get("condition") ?: JsonNodeFactory.instance.nullNode())
        return EntryDigest.sha256(content)
    }

    internal fun unconfirmed(statement: StatementSource): StatementSource =
        if (statement.onMissingConfirmed == null) {
            statement
        } else {
            StatementSource(
                statement.name,
                statement.effect,
                statement.actions,
                statement.resources,
                statement.condition,
                statement.onMissing
            )
        }
}
