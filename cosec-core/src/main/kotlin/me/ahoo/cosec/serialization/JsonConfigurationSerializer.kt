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

import me.ahoo.cosec.configuration.JsonConfiguration
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

object JsonConfigurationSerializer : StdSerializer<JsonConfiguration>(JsonConfiguration::class.java) {
    override fun serialize(value: JsonConfiguration, gen: JsonGenerator, provider: SerializationContext) {
        gen.writeTree(value.delegate)
    }
}

object JsonConfigurationDeserializer : StdDeserializer<JsonConfiguration>(JsonConfiguration::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): JsonConfiguration {
        return JsonConfiguration(p.readValueAsTree())
    }
}
