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
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.policy.action.ActionMatcherFactoryProvider
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory

object JsonActionMatcherSerializer : StdSerializer<ActionMatcher>(ActionMatcher::class.java) {
    override fun serialize(value: ActionMatcher, gen: JsonGenerator, provider: SerializerProvider) {
        if (value.configuration.isString || value.configuration.isArray) {
            gen.writePOJO(value.configuration)
            return
        }
        gen.writePOJOField(value.type, value.configuration)
    }
}

object JsonActionMatcherDeserializer : StdDeserializer<ActionMatcher>(ActionMatcher::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ActionMatcher {
        val actionConfiguration = p.codec.readValue(p, JsonConfiguration::class.java)

        if (actionConfiguration.isString && actionConfiguration.asString() == AllActionMatcher.ALL) {
            return AllActionMatcher
        }

        if (actionConfiguration.isString || actionConfiguration.isArray) {
            return PathActionMatcherFactory.INSTANCE.create(actionConfiguration)
        }

        require(actionConfiguration.isObject)
        /**
         * isObject
         */
        val field = actionConfiguration.delegate.fields().next()
        val conditionConfiguration = field.value.traverse(p.codec).readValueAs(JsonConfiguration::class.java)
        return ActionMatcherFactoryProvider.getRequired(field.key).create(conditionConfiguration)
    }
}
