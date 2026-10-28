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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * The host's attributes of one HTTP request (RFC 0005 §3.2, RFC 0006 §5.4): what the engine does not hold itself.
 *
 * - `http.header.<name>`: the header's first value, as 7.x's `Request.getHeader` read it; names arrive lower case.
 * - `http.origin`, `http.referer`: the `Origin` and `Referer` headers; `.host` is their URI's host, absent when the
 *   value is not a URI or has no host. The `.labels` attributes are derived from these by the conditions.
 * - `request.attributes.<name>`: what the adapter knows besides HTTP ([HttpFacts.requestAttributes]), such as the
 *   gateway's route id.
 *
 * Every other attribute is absent here: the engine answers it. Reads never throw (the [ConditionInput] contract).
 */
class HttpAttributes(private val facts: HttpFacts) : ConditionInput {
    override fun string(attribute: Attribute): String? = when (attribute.kind) {
        Attribute.Kind.HTTP_HEADER -> header(attribute.name)
        Attribute.Kind.HTTP_ORIGIN -> header(ORIGIN)
        Attribute.Kind.HTTP_REFERER -> header(REFERER)
        Attribute.Kind.HTTP_ORIGIN_HOST -> header(ORIGIN)?.let(::hostOf)
        Attribute.Kind.HTTP_REFERER_HOST -> header(REFERER)?.let(::hostOf)
        Attribute.Kind.REQUEST_ATTRIBUTE -> facts.requestAttributes[attribute.name]
        else -> null
    }

    override fun bool(attribute: Attribute): Boolean? = null

    override fun stringSet(attribute: Attribute): Set<String>? = null

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME

    private fun header(name: String): String? = facts.headers.values(name).firstOrNull()

    private fun hostOf(value: String): String? = try {
        URI(value).host
    } catch (_: URISyntaxException) {
        null
    }

    private companion object {
        const val ORIGIN = "origin"
        const val REFERER = "referer"
    }
}
