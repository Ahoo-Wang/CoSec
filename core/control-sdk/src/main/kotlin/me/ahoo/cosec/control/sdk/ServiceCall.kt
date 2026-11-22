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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * One call of the control API's narrow set (`CTL-65`), as its route takes it: the [method], the [path] under the API's
 * base (its variables already escaped as path segments), the [query] and the JSON [body]. [RoutedControlService] makes it,
 * once for every implementation, so the same call is the same request in process and over the network (`CTL-77`).
 */
@InternalCoSecApi
class ServiceCall(
    val method: String,
    val path: String,
    query: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    val query: Map<String, String> = query.toMap()

    override fun toString(): String = "$method $path${if (query.isEmpty()) "" else "?$query"}"
}
