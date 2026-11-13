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
package me.ahoo.cosec.social

import java.util.concurrent.ConcurrentHashMap

/**
 * The registry of [SocialAuthenticationProvider]s. One instance per application: the starter assembles one for each
 * Spring context, so one context's registrations never reach another (#1222).
 *
 * @author ahoo wang
 */
class SocialProviderManager {
    private val providers: ConcurrentHashMap<String, SocialAuthenticationProvider> = ConcurrentHashMap()

    operator fun get(provider: String): SocialAuthenticationProvider? {
        return providers[provider]
    }

    fun getRequired(provider: String): SocialAuthenticationProvider {
        return requireNotNull(get(provider))
    }

    fun register(provider: String, authProvider: SocialAuthenticationProvider) {
        providers[provider] = authProvider
    }

    fun register(authProvider: SocialAuthenticationProvider) {
        providers[authProvider.name] = authProvider
    }
}
