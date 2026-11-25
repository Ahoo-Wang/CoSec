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

package me.ahoo.cosec.compat

import java.util.Base64
import java.util.UUID

/** A writer and a reader of a format: across the two versions both ways, and within the current one. */
val PAIRS: List<Pair<Side, Side>> = listOf(
    Side.CURRENT to Side.PREVIOUS,
    Side.PREVIOUS to Side.CURRENT,
    Side.CURRENT to Side.CURRENT,
)

/** A key prefix of its own for one case, so cases never read one another's keys. */
fun prefix(): String = "compat:" + UUID.randomUUID()

/** The JSON of a `CoSec-Identity` header, and the header of a JSON. */
object HeaderJson {
    fun of(header: String): String = Base64.getUrlDecoder().decode(header).decodeToString()

    fun header(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())

    /** [json] with the top-level members [members] (each `"name":value`) added first. */
    fun adding(json: String, vararg members: String): String {
        check(json.startsWith("{")) { "not a JSON object: [${json.take(120)}]" }
        return "{" + members.joinToString("") { "$it," } + json.substring(1)
    }
}
