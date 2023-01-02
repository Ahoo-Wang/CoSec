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

package me.ahoo.cosec.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.policy.StatementData

const val STATEMENT_EFFECT_KEY = "effect"
const val STATEMENT_ACTIONS_KEY = "actions"
const val STATEMENT_CONDITIONS_KEY = "conditions"

object JsonStatementSerializer : StdSerializer<Statement>(Statement::class.java) {
    override fun serialize(value: Statement, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writePOJOField(STATEMENT_EFFECT_KEY, value.effect)
        if (value.actions.isNotEmpty()) {
            gen.writeArrayFieldStart(STATEMENT_ACTIONS_KEY)
            value.actions.forEach {
                gen.writeObject(it)
            }
            gen.writeEndArray()
        }
        if (value.conditions.isNotEmpty()) {
            gen.writeArrayFieldStart(STATEMENT_CONDITIONS_KEY)
            value.conditions.forEach {
                gen.writeObject(it)
            }
            gen.writeEndArray()
        }
        gen.writeEndObject()
    }
}

object JsonStatementDeserializer : StdDeserializer<Statement>(Statement::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Statement {
        val jsonNode = p.codec.readTree<JsonNode>(p)
        val actions = jsonNode.has(STATEMENT_ACTIONS_KEY).let { hasActions ->
            if (hasActions) {
                jsonNode.get(STATEMENT_ACTIONS_KEY).map {
                    it.traverse(p.codec).readValueAs(ActionMatcher::class.java)
                }.toSet()
            } else {
                emptySet()
            }
        }
        val conditions = jsonNode.has(STATEMENT_CONDITIONS_KEY).let { hasConditions ->
            if (hasConditions) {
                jsonNode.get(STATEMENT_CONDITIONS_KEY).map {
                    it.traverse(p.codec).readValueAs(ConditionMatcher::class.java)
                }.toSet()
            } else {
                emptySet()
            }
        }

        return StatementData(
            effect = jsonNode.get(STATEMENT_EFFECT_KEY).traverse(p.codec).readValueAs(Effect::class.java),
            actions = actions,
            conditions = conditions
        )
    }
}
