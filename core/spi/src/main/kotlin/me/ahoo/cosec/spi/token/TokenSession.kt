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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.model.Identifiers
import java.time.Instant

/**
 * The session both tokens of a pair belong to (RFC 0009): its family's [id] (`sid`), when its user signed in
 * ([authTime], `auth_time`), and which [generation] of the family the pair is (`cosec.generation`). A login starts a
 * family at generation 1; each refresh issues the next generation, and only the family's current one refreshes.
 */
class TokenSession(val id: String, val authTime: Instant, val generation: Long) {
    init {
        if (id.isEmpty() || !Identifiers.isWellFormed(id)) {
            throw TokenVerificationException("The token's session id is empty or holds an unpaired UTF-16 surrogate.")
        }
        if (generation < 1) {
            throw TokenVerificationException("The token's generation must be positive, not $generation.")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TokenSession && other.id == id && other.authTime == authTime && other.generation == generation

    override fun hashCode(): Int = (id.hashCode() * 31 + authTime.hashCode()) * 31 + generation.hashCode()

    override fun toString(): String = "TokenSession($id, generation $generation, signed in at $authTime)"
}
