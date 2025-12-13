package me.ahoo.cosec.serialization

import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

const val STATEMENT_NAME = "name"
const val STATEMENT_EFFECT_KEY = "effect"
const val STATEMENT_ACTION_KEY = "action"
const val STATEMENT_CONDITION_KEY = "condition"

abstract class AbstractJsonStatementSerializer<T : Statement>(statementType: Class<T>) :
    StdSerializer<T>(statementType) {
    override fun serialize(value: T, gen: JsonGenerator, provider: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty(STATEMENT_NAME, value.name)
        gen.writePOJOProperty(STATEMENT_EFFECT_KEY, value.effect)
        gen.writePOJOProperty(STATEMENT_ACTION_KEY, value.action)
        gen.writePOJOProperty(STATEMENT_CONDITION_KEY, value.condition)
        writeExtend(value, gen, provider)
        gen.writeEndObject()
    }

    protected open fun writeExtend(value: T, gen: JsonGenerator, provider: SerializationContext) = Unit
}

abstract class AbstractJsonStatementDeserializer<T : Statement>(statementType: Class<T>) :
    StdDeserializer<T>(statementType) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val jsonNode = p.objectReadContext().readTree<JsonNode>(p)
        val effect = jsonNode.get(STATEMENT_EFFECT_KEY)?.traverse(p.objectReadContext())
            ?.readValueAs(Effect::class.java)
            ?: Effect.ALLOW

        val action =
            requireNotNull(jsonNode.get(STATEMENT_ACTION_KEY)).traverse(p.objectReadContext())
                .readValueAs(ActionMatcher::class.java)

        val condition =
            jsonNode.get(STATEMENT_CONDITION_KEY)?.traverse(p.objectReadContext())
                ?.readValueAs(ConditionMatcher::class.java)
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
