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

package me.ahoo.cosec.runtime.attribute

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.condition.IpAddress
import java.util.concurrent.ConcurrentHashMap

/** A [BlacklistSource] in memory, for tests and small deployments. */
class InMemoryBlacklistSource : BlacklistSource {
    // One set per kind: a lookup allocates nothing, where a (kind, value) key would on every request.
    private val entries = BlacklistKind.entries.map { ConcurrentHashMap.newKeySet<String>() }

    override suspend fun contains(kind: BlacklistKind, value: String): Boolean = value in entries[kind.ordinal]

    /** Blocks [value] of [kind]; an address in any spelling, kept canonical ([BlacklistKind.IP]). */
    fun block(kind: BlacklistKind, value: String) {
        entries[kind.ordinal] += kept(kind, value)
    }

    fun unblock(kind: BlacklistKind, value: String) {
        entries[kind.ordinal] -= kept(kind, value)
    }

    private fun kept(kind: BlacklistKind, value: String): String {
        if (kind != BlacklistKind.IP) return value
        return requireNotNull(IpAddress.parse(value)) { "[${Identifiers.printable(value)}] is no address." }.toString()
    }
}
