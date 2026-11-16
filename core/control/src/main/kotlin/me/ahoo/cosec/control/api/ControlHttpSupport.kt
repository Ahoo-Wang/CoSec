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
import me.ahoo.cosec.control.api.ControlHttp.Companion.JSON
import me.ahoo.cosec.control.api.ControlRequestJson.query
import me.ahoo.cosec.control.api.ControlRequestJson.revisionOf
import me.ahoo.cosec.control.api.JsonFields.wellFormed
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

/**
 * What every route of the control API uses: reading a POST's body (one JSON object, no key twice, no unpaired
 * surrogate), the method a route takes, and the answers: a change, a document, JSON, a 404 for a path no route serves.
 */
internal object ControlHttpSupport {
    val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    inline fun post(request: ControlHttpRequest, block: (JsonNode) -> ControlHttpResponse): ControlHttpResponse {
        if (request.method != POST) return methodNotAllowed()
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { bytes ->
            try {
                reader.readTree(bytes)
            } catch (e: JacksonException) {
                // Not the parser's message: it is not one of the control plane's own (RC1-39).
                throw InvalidControlRequestException("The request body is not JSON, or holds a key twice.", e)
            }
        } ?: factory.objectNode()
        requireRequest(body.isObject) { "The request body is a JSON object." }
        requireRequest(wellFormed(body)) { UNPAIRED_SURROGATE }
        return block(body)
    }

    inline fun get(request: ControlHttpRequest, block: () -> ControlHttpResponse): ControlHttpResponse =
        if (request.method == GET) block() else methodNotAllowed()

    fun changeResponse(change: ChangeRequest, status: Int) =
        ControlHttpResponse(status, JSON, ChangeRequestCodec.encodeShown(change))

    fun document(document: me.ahoo.cosec.document.PolicyDocument) = ControlHttpResponse(
        OK,
        JSON,
        PolicyDocumentCodec.encode(document)
    )

    fun json(node: JsonNode, status: Int = OK) = ControlHttpResponse(
        status,
        JSON,
        CoSecJson.mapper.writeValueAsBytes(node)
    )

    fun notFound(): ControlHttpResponse = throw ChangeRejectedException(NOT_FOUND, "no such route")

    fun methodNotAllowed(): ControlHttpResponse = throw InvalidControlRequestException(
        "This route does not take that method."
    )

    /**
     * A path segment and its custom verb (`c1:publish`, `tenant:acme:apply`): the verb follows the last `:` that is not
     * part of a document key, whose `app:` or `tenant:` prefix has one of its own.
     */
    fun verbOf(segment: String): Pair<String, String?> {
        val keyed = segment.startsWith("app:") || segment.startsWith("tenant:")
        val separator = segment.indexOf(':', if (keyed) segment.indexOf(':') + 1 else 0)
        return if (separator < 0) segment to null else segment.substring(0, separator) to segment.substring(separator + 1)
    }

    fun ControlHttpRequest.revision(name: String): Long =
        query[name]?.let { revisionOf(name, it) } ?: throw InvalidControlRequestException("[$name] is a revision number.")

    const val UNPAIRED_SURROGATE = "The request holds a string with an unpaired UTF-16 surrogate, which " +
        "no id may hold (IDN-10)."
    const val GET = "GET"
    const val POST = "POST"
    const val DELETE = "DELETE"
    const val OK = 200
    const val CREATED = 201
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    val factory: JsonNodeFactory = JsonNodeFactory.instance
}
