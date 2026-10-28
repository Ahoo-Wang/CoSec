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

import me.ahoo.cosec.model.condition.HttpNames
import me.ahoo.cosec.model.scope.HostScope

/**
 * Everything an adapter hands to the pipeline (RFC 0006 §5.3.1). Immutable: the adapter reads it from its host request
 * once, and the pipeline never goes back to the host.
 */
class HttpFacts(
    val method: String,
    /** The path as received: not decoded, without the context path. [PathNormalizer] checks it. */
    val rawPath: String,
    /** The query string as received, without `?`; null when the request has none. */
    val rawQuery: String?,
    val headers: Headers,
    val cookies: Cookies,
    /** The peer address, before any forwarded-for resolution. */
    val remoteAddress: String,
    /** The app the host is configured for (RFC 0004 §4.6); for the gateway, the route's. */
    val host: HostScope,
    /** What the host knows about the request besides HTTP, such as the gateway's route id. */
    val requestAttributes: Map<String, String> = emptyMap(),
)

/** Request headers. Names are case-insensitive (RFC 9110 §5.1). */
interface Headers {
    /** Every value of [name] in received order; empty when absent. */
    fun values(name: String): List<String>

    companion object {
        val EMPTY: Headers = of(emptyMap())

        /** Headers backed by [values]; the map is copied and its keys folded as the documents fold them ([HttpNames]). */
        fun of(values: Map<String, List<String>>): Headers {
            val folded = values.entries.groupBy({ HttpNames.fold(it.key) }, { it.value })
                .mapValues { (_, lists) -> lists.flatten() }
            return object : Headers {
                override fun values(name: String): List<String> = folded[HttpNames.fold(name)].orEmpty()
            }
        }
    }
}

/** Request cookies. Names are case-sensitive (RFC 6265 §5.3). */
interface Cookies {
    /** Every value of the cookie [name]; empty when absent. */
    fun values(name: String): List<String>

    companion object {
        val EMPTY: Cookies = of(emptyMap())

        fun of(values: Map<String, List<String>>): Cookies {
            val copy = values.toMap()
            return object : Cookies {
                override fun values(name: String): List<String> = copy[name].orEmpty()
            }
        }
    }
}
