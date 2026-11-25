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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemType

/**
 * What a client sends and sees, whichever version answers it: `parameters.read` reads the `CoSec-*` parameters of a
 * request as this version does (MIX-21), the headers given as `name=value` lines; `problems` lists the problems this
 * version answers a client with, each with its status and type (MIX-18).
 */
internal object EdgeOperations {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "parameters.read" to { a ->
            val headers = a.getValue("headers").lines().filter { it.isNotEmpty() }
                .groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
            val facts = HttpFacts("GET", "/orders/1", null, Headers.of(headers), Cookies.of(emptyMap()), "127.0.0.1", HostScope(null))
            CoSecParameters.read(facts)?.let { "space=${it.spaceId};device=${it.deviceId}" } ?: "ambiguous"
        },
        "problems" to { _ ->
            ProblemType.entries.joinToString("\n") { "${it.code} ${it.status} ${it.uri} ${it.title}" }
        },
    )
}
