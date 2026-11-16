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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.model.PolicyId
import tools.jackson.databind.node.ObjectNode

/** A policy, both ways: its header and conditions here, its statements through [StatementCodec]. */
internal object PolicyCodec {
    private val POLICY_FIELDS = arrayOf("id", "attachable", "boundary", "condition", "statements")

    internal fun decode(node: DocumentNode): PolicyDefinition {
        node.requireObject(*POLICY_FIELDS)
        return PolicyDefinition(
            id = PolicyId(node.required("id").text()),
            attachable = node.field("attachable")?.bool() ?: false,
            condition = node.field("condition")?.let(ConditionJson::read),
            statements = node.required("statements").elements().map(StatementCodec::decode),
            boundary = node.field("boundary")?.bool() ?: false,
        )
    }

    internal fun tree(policy: PolicyDefinition): ObjectNode {
        val node = factory.objectNode().put("id", policy.id.value)
        if (policy.attachable) node.put("attachable", true)
        if (policy.boundary) node.put("boundary", true)
        policy.condition?.let { node.set("condition", ConditionJson.write(it)) }
        val statements = node.putArray("statements")
        for (statement in policy.statements) {
            statements.add(StatementCodec.tree(statement))
        }
        return node
    }
}
