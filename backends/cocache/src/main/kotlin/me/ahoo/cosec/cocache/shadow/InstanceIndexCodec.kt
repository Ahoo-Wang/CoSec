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

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import java.time.Instant

/** The instances that have a status, as a JSON object of instance id to the time it listed itself. */
internal object InstanceIndexCodec : Codec<Map<String, Instant>> {
    override fun encode(value: Map<String, Instant>): ByteArray =
        CoSecJson.mapper.writeValueAsBytes(value.toSortedMap().mapValues { it.value.toString() })

    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = CoSecJson.mapper.readTree(bytes)
        require(
            node.isObject && node.values().all { it.isString }
        ) { "The instances with a status are an object of instance id to time" }
        return node.properties().associate { (instance, at) -> instance to Instant.parse(at.asString()) }
    }
}
