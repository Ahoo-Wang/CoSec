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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
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
    /**
     * The client address, read once by the adapter's [RemoteIpResolver] as the starter decides (`PIP-16`): the peer, the
     * trusted `X-Forwarded-For` entry or what the server resolved, written as an address alone ([ClientAddress]). Not
     * an address (`unknown`) when it could not be read: `environment.ip` is then unavailable (`PIP-17`).
     */
    val remoteAddress: String,
    /**
     * The app the host is configured for (RFC 0004 §4.6, [HostScope]): `cosec.authorization.scope.app-id`. On a gateway,
     * [routeApp] takes precedence (RC1-6).
     */
    val host: HostScope,
    /** What the host knows about the request besides HTTP, such as the gateway's route id. */
    val requestAttributes: Map<String, String> = emptyMap(),
    /**
     * On a gateway, the matched route's metadata `cosec-app-id` as configured, of whatever type the configuration gave
     * it: unchecked, as adapters only translate. The pipeline makes it the host app ahead of [host], and refuses the
     * request as an unmodelled error (500) when it is not a [String] (a number, a list or a map, R1-7 of #1194's review)
     * or cannot be an app id (RC1-6, RC1-15). Null elsewhere, and for a route without it.
     */
    val routeApp: Any? = null,
)

/** Request headers. Names are case-insensitive (RFC 9110 §5.1). */
interface Headers {
    /** Every value of [name] in received order; empty when absent. */
    fun values(name: String): List<String>

    companion object {
        val EMPTY: Headers = of(emptyMap())

        /** Headers backed by [values]; the map is copied and its keys folded as the documents fold them ([HttpNames]). */
        fun of(values: Map<String, List<String>>): Headers {
            // One pass: a value list is copied once, when its name is first seen, and merged only when a name repeats
            // under another case. Sized so a typical request's headers never rehash.
            val folded = HashMap<String, List<String>>(values.size + values.size / 3 + 1)
            for ((name, incoming) in values) {
                val key = HttpNames.fold(name)
                val previous = folded[key]
                folded[key] = if (previous == null) incoming.toList() else previous + incoming
            }
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
