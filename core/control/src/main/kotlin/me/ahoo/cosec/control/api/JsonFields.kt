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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode
import java.time.DateTimeException

/**
 * Reading the fields of a control API request's JSON body (RFC 0007 §10.2): each required or optional, of the type it
 * must be, and an object only with the fields it may have. A field that is not what it must be is a 400 naming it, never
 * a parser's or an exception's message (RC1-39).
 */
internal object JsonFields {
    fun JsonNode.need(name: String): JsonNode = get(name)?.takeUnless {
        it.isNull
    } ?: throw InvalidControlRequestException("[$name] is missing.")

    /** The string field [name], required. */
    fun JsonNode.text(name: String): String = need(name).stringIn(name)

    /** The string field [name], or null when it is absent. */
    fun JsonNode.optText(name: String): String? = get(name)?.stringIn(name)

    fun JsonNode.long(name: String): Long = need(name).longIn(name)

    fun JsonNode.optLong(name: String): Long? = get(name)?.longIn(name)

    fun JsonNode.optBool(name: String): Boolean? = get(name)?.let {
        requireRequest(it.isBoolean) { "[$name] is not a boolean." }
        it.asBoolean()
    }

    fun JsonNode.strings(name: String): List<String> =
        get(name)?.let { arrayOf(name, it) }?.map { it.stringIn(name) }.orEmpty()

    fun JsonNode.array(name: String): List<JsonNode> = arrayOf(name, need(name))

    fun arrayOf(name: String, node: JsonNode): List<JsonNode> {
        requireRequest(node.isArray) { "[$name] is not an array." }
        return node.values().toList()
    }

    fun objectOf(name: String, node: JsonNode): JsonNode {
        requireRequest(node.isObject) { "[$name] is not an object." }
        return node
    }

    /** This value, of the field [name], as a string. */
    fun JsonNode.stringIn(name: String): String {
        requireRequest(isString) { "[$name] is not a string." }
        return asString()
    }

    /** This value, of the field [name], as an integer. */
    fun JsonNode.longIn(name: String): Long {
        requireRequest(isIntegralNumber && canConvertToLong()) { "[$name] is not an integer." }
        return asLong()
    }

    /** This object, the field [name], has only [names]. */
    fun JsonNode.only(name: String, vararg names: String) {
        requireRequest(isObject) { "[$name] is not an object." }
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        requireRequest(unknown.isEmpty()) { "Unknown fields $unknown in [$name]." }
    }

    /**
     * [parse] of the client's field [name]. What it throws is not shown, being no message of the control plane's own:
     * only that [name] is not [what] (RC1-39), unless it is one of those checks.
     */
    inline fun <T> field(name: String, what: String, parse: () -> T): T = try {
        parse()
    } catch (e: InvalidControlRequestException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    } catch (e: ArithmeticException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    } catch (e: DateTimeException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    }

    /**
     * [decode] of the client's own [name]: the codec's format errors describe what the caller sent, by its JSON path, so
     * they are the request's validation and say so (RC1-39).
     */
    inline fun <T> decoded(name: String, decode: () -> T): T = try {
        decode()
    } catch (e: SnapshotFormatException) {
        throw InvalidControlRequestException("[$name]: ${e.message}", e)
    }

    /**
     * Whether every field name and string in [node] holds no unpaired surrogate (RC1-52): every id the body names is
     * one of them, a document's included, and a JSON escape (`"\uD800"`) can write one.
     */
    fun wellFormed(node: JsonNode): Boolean = when {
        node.isString -> Identifiers.isWellFormed(node.asString())
        node.isObject -> node.properties().all { (name, value) -> Identifiers.isWellFormed(name) && wellFormed(value) }
        node.isArray -> node.values().all(::wellFormed)
        else -> true
    }
}
