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

package me.ahoo.cosec.migrate.v7.configuration

import me.ahoo.cosec.api.Delegated
import me.ahoo.cosec.migrate.v7.api.configuration.Configuration
import me.ahoo.cosec.migrate.v7.serialization.V7JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.StringNode

/**
 * JSON-based implementation of [Configuration].
 *
 * This class wraps a Jackson JsonNode and provides type-safe
 * access to configuration values.
 *
 * @param delegate The underlying JsonNode
 * @see Configuration
 */
internal class JsonConfiguration(
    override val delegate: JsonNode
) : Configuration,
    Delegated<JsonNode> {
    companion object {
        val NULL: JsonConfiguration by lazy {
            JsonConfiguration(
                NullNode.getInstance(),
            )
        }

        fun newPojoConfiguration(): JsonConfiguration = JsonConfiguration(V7JsonSerializer.createObjectNode())

        fun Map<String, *>.asConfiguration(): JsonConfiguration {
            val jsonString = V7JsonSerializer.writeValueAsString(this)
            return JsonConfiguration(V7JsonSerializer.readTree(jsonString))
        }

        fun String.asConfiguration(): JsonConfiguration = JsonConfiguration(StringNode(this))
    }

    override fun get(key: String): Configuration? = delegate.get(key)?.let { JsonConfiguration(it) }

    override fun asList(): List<Configuration> =
        delegate.values().map {
            JsonConfiguration(it)
        }

    override fun asMap(): Map<String, Configuration> =
        buildMap {
            delegate.properties().forEach {
                put(it.key, JsonConfiguration(it.value))
            }
        }

    override fun asString(): String = delegate.asString()

    override fun asBoolean(): Boolean = delegate.asBoolean()

    override fun asInt(): Int = delegate.asInt()

    override fun asLong(): Long = delegate.asLong()

    override fun asDouble(): Double = delegate.asDouble()

    override fun <T> asObject(objectClass: Class<T>): T = V7JsonSerializer.treeToValue<T>(delegate, objectClass)

    override val isString: Boolean
        get() = delegate.isString
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
