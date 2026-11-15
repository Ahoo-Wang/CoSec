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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import java.time.Instant

/** Listed entries and when each was listed, as a JSON object sorted by entry, so equal lists have equal digests. */
internal object ListedCodec : Codec<Map<String, Instant>> {
    private const val WHAT = "control list"

    override fun encode(value: Map<String, Instant>): ByteArray {
        val node = factory.objectNode()
        value.entries.sortedBy { it.key }.forEach { (entry, listedAt) -> node.put(entry, listedAt.toString()) }
        return CoSecJson.mapper.writeValueAsBytes(node)
    }

    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = read(bytes, WHAT)
        if (!node.isObject) malformed(WHAT, "$node is not an object")
        return node.properties().associateTo(LinkedHashMap()) { (entry, listedAt) -> entry to listedAt.instant(WHAT) }
    }
}
