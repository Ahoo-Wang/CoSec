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

package me.ahoo.cosec.system

import java.util.Base64

/**
 * The `CoSec-Identity` header a service behind the gateway received, decoded from its documented wire format (base64url
 * of compact JSON, `schema/8.0/cosec-identity.schema.json`) with nothing of CoSec's: the scenarios check what the gateway
 * writes, not what CoSec's own codec reads back.
 */
object ForwardedIdentity {
    @Suppress("UNCHECKED_CAST")
    fun decode(header: String): Map<String, Any?> =
        Json.mapper.readValue(Base64.getUrlDecoder().decode(header), Map::class.java) as Map<String, Any?>
}
