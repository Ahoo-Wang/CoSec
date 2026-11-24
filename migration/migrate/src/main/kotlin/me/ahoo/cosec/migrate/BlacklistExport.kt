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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId

/**
 * `--blacklist` (DR2-M-9, `MIG-6`): the principals the application's blacklist blocks, as a JSON array of the values its
 * `BlacklistSource` holds for them, `"<tenant>:<principal id>"`. The blacklist names a principal by its id, as a DENY
 * may: a key moved to a principal of its own would no longer be blocked, so a key whose principal is here stays on it.
 */
object BlacklistExport {
    /** The values of [json]; a file that is not an array of non-empty strings is refused. */
    fun read(json: ByteArray): Set<String> {
        val node = CoSecJson.mapper.readTree(json)
        require(node != null && node.isArray) { "--blacklist is a JSON array of \"<tenant>:<principal id>\" values." }
        return node.values().mapIndexedTo(LinkedHashSet()) { index, value ->
            require(value.isString && value.asString().isNotEmpty()) {
                "--blacklist entry $index is not a \"<tenant>:<principal id>\" string."
            }
            value.asString()
        }
    }

    /** The value the blacklist blocks [principal] of [tenant] by, as `BlacklistKind.principal` writes it. */
    fun value(tenant: TenantId, principal: String): String = "${tenant.value}:$principal"
}
