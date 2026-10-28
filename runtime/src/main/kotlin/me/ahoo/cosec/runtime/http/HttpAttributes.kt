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
import java.util.Locale

/**
 * The host's attributes of one HTTP request (RFC 0005 §3.2, RFC 0006 §5.4): what the engine does not hold itself.
 *
 * - `http.header.<name>`: the header's value; names arrive lower case. A header sent more than once with different
 *   values has no single value: reading it throws [AmbiguousHeaderException], so a DENY on it is indeterminate and an
 *   ALLOW on it does not match, rather than the first (or last) value deciding. Identical repeats are one value.
 * - `http.origin`, `http.referer`: the `Origin` and `Referer` headers, read the same way; `.host` is their URI's host
 *   in lower case (hosts are case-insensitive), absent when the value is not a URI or has no host. The `.labels`
 *   attributes are derived from these by the conditions.
 * - `request.attributes.<name>`: what the adapter knows besides HTTP ([HttpFacts.requestAttributes]), such as the
 *   gateway's route id.
 *
 * Every other attribute is absent here: the engine answers it. Only an ambiguous header throws; the engine counts the
 * statement that read it as erring.
 */
class HttpAttributes(
    private val headers: Headers,
    private val requestAttributes: Map<String, String> = emptyMap(),
) : ConditionInput {
    constructor(facts: HttpFacts) : this(facts.headers, facts.requestAttributes)

    override fun string(attribute: Attribute): String? = when (attribute.kind) {
        Attribute.Kind.HTTP_HEADER -> header(attribute.name)
        Attribute.Kind.HTTP_ORIGIN -> header(ORIGIN)
        Attribute.Kind.HTTP_REFERER -> header(REFERER)
        Attribute.Kind.HTTP_ORIGIN_HOST -> header(ORIGIN)?.let(::hostOf)
        Attribute.Kind.HTTP_REFERER_HOST -> header(REFERER)?.let(::hostOf)
        Attribute.Kind.REQUEST_ATTRIBUTE -> requestAttributes[attribute.name]
        else -> null
    }

    override fun bool(attribute: Attribute): Boolean? = null

    override fun stringSet(attribute: Attribute): Set<String>? = null

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME

    private fun header(name: String): String? {
        val values = headers.values(name)
        val first = values.firstOrNull() ?: return null
        for (i in 1 until values.size) {
            if (values[i] != first) {
                throw AmbiguousHeaderException(name)
            }
        }
        return first
    }

    private fun hostOf(value: String): String? = try {
        URI(value).host?.lowercase(Locale.ROOT)
    } catch (_: URISyntaxException) {
        null
    }

    private companion object {
        const val ORIGIN = "origin"
        const val REFERER = "referer"
    }
}

/** Header [header] arrived more than once with different values: a condition on it cannot tell which one to read. */
class AmbiguousHeaderException(val header: String) :
    IllegalArgumentException("Header [$header] arrived with different values; a condition on it cannot decide.")
