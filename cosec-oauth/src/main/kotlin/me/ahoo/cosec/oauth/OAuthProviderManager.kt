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
package me.ahoo.cosec.oauth

import com.google.common.annotations.Beta
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuthProviderManager .
 *
 * @author ahoo wang
 */
@Beta
class OAuthProviderManager {
    private val providers: ConcurrentHashMap<String, OAuthProvider> = ConcurrentHashMap()

    operator fun get(provider: String): OAuthProvider? {
        return providers[provider]
    }

    fun getRequired(provider: String): OAuthProvider {
        return requireNotNull(get(provider))
    }

    fun register(provider: String, authProvider: OAuthProvider) {
        providers[provider] = authProvider
    }

    fun register(authProvider: OAuthProvider) {
        providers[authProvider.name] = authProvider
    }

    companion object {
        @JvmField
        val INSTANCE = OAuthProviderManager()
    }
}
