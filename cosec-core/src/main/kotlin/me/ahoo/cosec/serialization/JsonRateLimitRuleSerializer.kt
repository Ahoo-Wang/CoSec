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

import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

const val RATE_LIMIT_RULE_ID_KEY = "id"
const val RATE_LIMIT_RULE_KEY_KEY = "key"
const val RATE_LIMIT_RULE_PERMITS_PER_SECOND_KEY = "permitsPerSecond"
const val RATE_LIMIT_RULE_WINDOW_SECONDS_KEY = "windowSeconds"
const val RATE_LIMIT_RULE_STRICT_FAILURE_KEY = "strictFailure"

object JsonRateLimitRuleSerializer : StdSerializer<RateLimitRule>(RateLimitRule::class.java) {
    override fun serialize(value: RateLimitRule, gen: JsonGenerator, provider: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty(RATE_LIMIT_RULE_ID_KEY, value.id)
        gen.writePOJOProperty(STATEMENT_ACTION_KEY, value.action)
        gen.writePOJOProperty(STATEMENT_CONDITION_KEY, value.condition)
        value.key?.let { gen.writeStringProperty(RATE_LIMIT_RULE_KEY_KEY, it) }
        gen.writeNumberProperty(RATE_LIMIT_RULE_PERMITS_PER_SECOND_KEY, value.rateLimit.permitsPerSecond)
        gen.writeNumberProperty(RATE_LIMIT_RULE_WINDOW_SECONDS_KEY, value.rateLimit.windowSeconds)
        gen.writeBooleanProperty(RATE_LIMIT_RULE_STRICT_FAILURE_KEY, value.strictFailure)
        gen.writeEndObject()
    }
}

object JsonRateLimitRuleDeserializer : StdDeserializer<RateLimitRule>(RateLimitRule::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): RateLimitRule {
        val jsonNode = p.objectReadContext().readTree<JsonNode>(p)
        val id = requireNotNull(jsonNode.get(RATE_LIMIT_RULE_ID_KEY)) {
            "$RATE_LIMIT_RULE_ID_KEY is required!"
        }.asString()
        val action = requireNotNull(jsonNode.get(STATEMENT_ACTION_KEY)) {
            "Rate limit rule [$id] $STATEMENT_ACTION_KEY is required!"
        }.traverse(p.objectReadContext()).readValueAs(ActionMatcher::class.java)
        val condition = jsonNode.get(STATEMENT_CONDITION_KEY)?.traverse(p.objectReadContext())
            ?.readValueAs(ConditionMatcher::class.java)
            ?: AllConditionMatcher.INSTANCE
        val permitsPerSecond = requireNotNull(jsonNode.get(RATE_LIMIT_RULE_PERMITS_PER_SECOND_KEY)) {
            "Rate limit rule [$id] $RATE_LIMIT_RULE_PERMITS_PER_SECOND_KEY is required!"
        }.asDouble()
        return RateLimitRule(
            id = id,
            action = action,
            condition = condition,
            key = jsonNode.get(RATE_LIMIT_RULE_KEY_KEY)?.asString(),
            rateLimit = RateLimit(
                permitsPerSecond = permitsPerSecond,
                windowSeconds = jsonNode.get(RATE_LIMIT_RULE_WINDOW_SECONDS_KEY)?.asLong() ?: 1,
            ),
            strictFailure = jsonNode.get(RATE_LIMIT_RULE_STRICT_FAILURE_KEY)?.asBoolean() ?: false,
        )
    }
}
