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
import me.ahoo.cosec.policy.condition.AllConditionMatcher

const val STATEMENT_NAME = "name"
const val STATEMENT_EFFECT_KEY = "effect"
const val STATEMENT_ACTION_KEY = "action"
const val STATEMENT_CONDITION_KEY = "condition"

abstract class AbstractJsonStatementSerializer<T : Statement>(statementType: Class<T>) :
    StdSerializer<T>(statementType) {
    override fun serialize(value: T, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField(STATEMENT_NAME, value.name)
        gen.writePOJOField(STATEMENT_EFFECT_KEY, value.effect)
        gen.writePOJOField(STATEMENT_ACTION_KEY, value.action)
        gen.writePOJOField(STATEMENT_CONDITION_KEY, value.condition)
        writeExtend(value, gen, provider)
        gen.writeEndObject()
    }

    protected open fun writeExtend(value: T, gen: JsonGenerator, provider: SerializerProvider) = Unit
}

abstract class AbstractJsonStatementDeserializer<T : Statement>(statementType: Class<T>) :
    StdDeserializer<T>(statementType) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val jsonNode = p.codec.readTree<JsonNode>(p)
        val effect = jsonNode.get(STATEMENT_EFFECT_KEY)?.traverse(p.codec)
            ?.readValueAs(Effect::class.java)
            ?: Effect.ALLOW

        val action =
            requireNotNull(jsonNode.get(STATEMENT_ACTION_KEY)).traverse(p.codec).readValueAs(ActionMatcher::class.java)

        val condition =
            jsonNode.get(STATEMENT_CONDITION_KEY)?.traverse(p.codec)?.readValueAs(ConditionMatcher::class.java)
                ?: AllConditionMatcher.INSTANCE

        return createStatement(
            jsonNode = jsonNode,
            name = jsonNode.get(STATEMENT_NAME)?.asText().orEmpty(),
            effect = effect,
            action = action,
            condition = condition,
        )
    }

    abstract fun createStatement(
        jsonNode: JsonNode,
        name: String,
        effect: Effect,
        action: ActionMatcher,
        condition: ConditionMatcher
    ): T
}
