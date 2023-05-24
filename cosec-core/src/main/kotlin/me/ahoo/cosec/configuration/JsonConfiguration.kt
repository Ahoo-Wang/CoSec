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

package me.ahoo.cosec.configuration

import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.serialization.CoSecJsonSerializer

class JsonConfiguration(
    override val delegate: JsonNode,
    private val objectCodec: ObjectCodec
) : Configuration,
    Delegated<JsonNode> {

    companion object {
        val NULL: JsonConfiguration by lazy {
            JsonConfiguration(NullNode.getInstance(), CoSecJsonSerializer)
        }

        fun newPojoConfiguration(): JsonConfiguration {
            return JsonConfiguration(ObjectNode(CoSecJsonSerializer.nodeFactory), CoSecJsonSerializer)
        }

        fun Map<String, *>.asConfiguration(): JsonConfiguration {
            val jsonString = CoSecJsonSerializer.writeValueAsString(this)
            return JsonConfiguration(CoSecJsonSerializer.readTree(jsonString), CoSecJsonSerializer)
        }

        fun String.asConfiguration(): JsonConfiguration {
            return JsonConfiguration(TextNode(this), CoSecJsonSerializer)
        }
    }

    override fun get(key: String): Configuration? {
        return delegate.get(key)?.let { JsonConfiguration(it, objectCodec) }
    }

    override fun asList(): List<Configuration> {
        return buildList {
            val elements = delegate.elements()
            while (elements.hasNext()) {
                add(JsonConfiguration(elements.next(), objectCodec))
            }
        }
    }

    override fun asMap(): Map<String, Configuration> {
        return buildMap {
            val fields = delegate.fields()
            while (fields.hasNext()) {
                val field = fields.next()
                put(field.key, JsonConfiguration(field.value, objectCodec))
            }
        }
    }

    override fun asString(): String {
        return delegate.asText()
    }

    override fun asBoolean(): Boolean {
        return delegate.asBoolean()
    }

    override fun asInt(): Int {
        return delegate.asInt()
    }

    override fun asLong(): Long {
        return delegate.asLong()
    }

    override fun asDouble(): Double {
        return delegate.asDouble()
    }

    override fun <T> asObject(objectClass: Class<T>): T {
        return delegate.traverse(objectCodec).use {
            it.readValueAs(objectClass)
        }
    }

    override val isString: Boolean
        get() = delegate.isTextual
    override val isBoolean: Boolean
        get() = delegate.isBoolean
    override val isInt: Boolean
        get() = delegate.isInt
    override val isLong: Boolean
        get() = delegate.isLong
    override val isDouble: Boolean
        get() = delegate.isDouble
    override val isArray: Boolean
        get() = delegate.isArray
    override val isObject: Boolean
        get() = delegate.isObject
}
