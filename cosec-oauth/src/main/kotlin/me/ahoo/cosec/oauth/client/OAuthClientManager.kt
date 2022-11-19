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
package me.ahoo.cosec.oauth.client

import com.google.common.annotations.Beta
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuthClientManager .
 *
 * @author ahoo wang
 */
@Beta
class OAuthClientManager {
    private val clients: ConcurrentHashMap<String, OAuthClient> = ConcurrentHashMap()

    operator fun get(client: String): OAuthClient? {
        return clients[client]
    }

    fun getRequired(client: String): OAuthClient {
        return requireNotNull(get(client))
    }

    fun register(client: String, authProvider: OAuthClient) {
        clients[client] = authProvider
    }

    fun register(authProvider: OAuthClient) {
        clients[authProvider.name] = authProvider
    }

    companion object {
        @JvmField
        val INSTANCE = OAuthClientManager()
    }
}
