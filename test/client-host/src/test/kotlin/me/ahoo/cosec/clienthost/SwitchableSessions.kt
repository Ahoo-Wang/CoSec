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

package me.ahoo.cosec.clienthost

import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore

/**
 * The host's session store: in memory, until a test takes it down. Down, every call fails as a store that cannot be
 * reached does, so a refresh or a logout is answered `503` and ends nothing (`IDN-19`).
 */
internal class SwitchableSessions(private val store: TokenFamilyStore = InMemoryTokenFamilyStore()) : TokenFamilyStore {
    @Volatile
    var down = false

    override suspend fun create(family: TokenFamily): Boolean = up { store.create(family) }

    override suspend fun read(id: String): TokenFamilyStore.Read? = up { store.read(id) }

    override suspend fun replace(version: String, next: TokenFamily): Boolean = up { store.replace(version, next) }

    private inline fun <T> up(call: () -> T): T {
        check(!down) { "The client host's session store is down." }
        return call()
    }
}
