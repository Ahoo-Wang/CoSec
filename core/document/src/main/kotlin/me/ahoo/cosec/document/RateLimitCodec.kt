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
import me.ahoo.cosec.document.DocumentJson.nonEmpty
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.RateLimitSpec
import tools.jackson.databind.node.ObjectNode

/** A rate limit rule, both ways: its statement is [StatementCodec]'s, its key and quota are its own. */
internal object RateLimitCodec {
    private val RATE_LIMIT_FIELDS = arrayOf(
        "id", "action", "resource", "condition", "onMissing", "key", "permitsPerSecond", "windowSeconds", "strictFailure",
    )

    /** `{"id", "action", "resource", "condition", "onMissing", "key", "permitsPerSecond", "windowSeconds", "strictFailure"}`. */
    internal fun decode(node: DocumentNode): RateLimitSource {
        node.requireObject(*RATE_LIMIT_FIELDS)
        val id = node.required("id").text()
        val permitsPerSecond = node.required("permitsPerSecond")
        val windowSeconds = node.field("windowSeconds")
        val limit = try {
            RateLimitSpec(permitsPerSecond.double(), windowSeconds?.long() ?: 1)
        } catch (error: IllegalArgumentException) {
            node.fail(error.message ?: "an invalid quota")
        }
        return RateLimitSource(
            id = id,
            statement = StatementSource(
                name = id,
                effect = Effect.DENY,
                actions = node.required("action").strings(),
                resources = node.field("resource")?.let(StatementCodec::decodeResources).orEmpty(),
                condition = node.field("condition")?.let(ConditionJson::read),
                onMissing = node.field("onMissing")?.let(StatementCodec::decodeOnMissing),
            ),
            key = node.field("key")?.nonEmpty(),
            limit = limit,
            strictFailure = node.field("strictFailure")?.bool() ?: false,
        )
    }

    internal fun tree(rule: RateLimitSource): ObjectNode {
        val written = StatementCodec.tree(rule.statement)
        val node = factory.objectNode().put("id", rule.id)
        listOf("action", "resource", "condition", "onMissing").forEach { field ->
            written.get(field)?.let { node.set(field, it) }
        }
        rule.key?.let { node.put("key", it) }
        node.put("permitsPerSecond", rule.limit.permitsPerSecond)
        node.put("windowSeconds", rule.limit.windowSeconds)
        if (rule.strictFailure) node.put("strictFailure", true)
        return node
    }
}
